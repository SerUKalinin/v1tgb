package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.*;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskStatePort riskStatePort;
    private final RiskStateReducer reducer;
    private final RiskReservationLogPort riskReservationLogPort;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;
   private final ExecutionLogger executionLogger;
    private final OutboxService outboxService;

    public RiskService(
            RiskStatePort riskStatePort,
            RiskStateReducer reducer,
            RiskReservationLogPort riskReservationLogPort,
            ExchangeFeasibilityPort feasibilityPort,
            OrderNormalizationService normalizationService,
            ExecutionLogger executionLogger,
            OutboxService outboxService
    ) {
        this.riskStatePort = riskStatePort;
        this.reducer = reducer;
        this.riskReservationLogPort = riskReservationLogPort;
        this.feasibilityPort = feasibilityPort;
        this.normalizationService = normalizationService;
        this.executionLogger = executionLogger;
        this.outboxService = outboxService;
    }

    // ==================== MAIN FLOW ====================

    public Optional<Order> evaluateAndReserve(SignalEvent signal) {
        log.info("[TRACE_FLOW] ENTER RiskService.evaluateAndReserve for signal: {}", signal.getSignalId());

        RiskState state = riskStatePort.get();
        log.info("[TRACE_FLOW] Current RiskState: halted={}, balance={}, reserved={}",
                state.isHalted(), state.getBalance(), state.getReservedMargin());

        if (state.isHalted()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: System is HALTED");
            return Optional.empty();
        }

        // 1. Расчет объема
        BigDecimal rawQuantity = calculateQuantity(signal, state);
        log.info("[TRACE_FLOW] Calculated raw quantity: {}", rawQuantity);

        // 2. Нормализация под требования биржи
        NormalizedOrder normalized = normalizationService.normalize(
                new FeasibilityRequest(signal.getSymbol(), rawQuantity, signal.getPrice())
        );
        log.info("[TRACE_FLOW] Normalized order: qty={}, price={}", normalized.getQuantity(), normalized.getPrice());

        // 3. Проверка возможности исполнения (Feasibility)
        FeasibilityResult feasibility = feasibilityPort.check(
                new FeasibilityRequest(
                        normalized.getSymbol(),
                        normalized.getQuantity(),
                        normalized.getPrice()
                )
        );
        log.info("[TRACE_FLOW] Feasibility check: feasible={}, reason={}", feasibility.isFeasible(), feasibility.getReason());

        if (!feasibility.isFeasible()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: Not feasible. Reason: {}", feasibility.getReason());
            return Optional.empty();
        }

        // 4. Проверка лимитов капитала (Risk Policy)
        BigDecimal requiredCapital = normalized.getQuantity().multiply(normalized.getPrice());
        UUID orderId = UUID.randomUUID();

        RiskDecision decision = RiskPolicy.canReserve(state, orderId, requiredCapital);
        log.info("[TRACE_FLOW] RiskPolicy decision: approved={}, reason={}", decision.isApproved(), decision.getReason());

        if (!decision.isApproved()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: Policy violation. Reason: {}", decision.getReason());
            return Optional.empty();
        }

        // 5. Резервирование капитала
        UUID eventId = UUID.randomUUID();
        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                eventId.toString(),
                orderId,
                requiredCapital
        );

        RiskState newState = reducer.reduce(state, event);

        log.info("[TRACE_FLOW] Persisting risk state change...");
        riskStatePort.markEventProcessed(
                eventId,
                newState,
                event
        );
        log.info("[TRACE_FLOW] Risk state persisted");

        logReservation(orderId, RiskReservationEventType.RESERVE, requiredCapital);
        // 6. Создание доменного объекта Order
        Order order = Order.createPendingExecution(
                orderId,
                ClientOrderIdGenerator.generate(orderId),
                signal.getSymbol(),
                signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL,
                OrderType.MARKET,
                normalized.getQuantity(),
                normalized.getPrice(),
                signal.getStrategyId(),
                signal.getSignalId()
        );
        log.info("[TRACE_FLOW] EXIT RiskService.evaluateAndReserve - APPROVED: {}", orderId);

        return Optional.of(order);
    }

    // ==================== EVENTS ====================

    public void publish(RiskEvent event) {
        RiskState state = riskStatePort.get();

        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            return;
        }

        UUID eventId;
        try {
            eventId = UUID.fromString(event.getEventId());
        } catch (IllegalArgumentException e) {
            eventId = UUID.nameUUIDFromBytes(event.getEventId().getBytes());
        }

        if (riskStatePort.isEventProcessed(eventId)) {
            return;
        }

        BigDecimal reservedBefore = null;
        if (event instanceof RiskEvent.CapitalReleased e) {
            reservedBefore = state.getActiveReservations().get(e.orderId());
        }

        RiskState newState = reducer.reduce(state, event);
        riskStatePort.markEventProcessed(eventId, newState, event);

        if (event instanceof RiskEvent.CapitalReserved e) {
            logReservation(e.orderId(), RiskReservationEventType.RESERVE, e.amount());
        }

        if (event instanceof RiskEvent.CapitalReleased e && reservedBefore != null) {
            logReservation(e.orderId(), RiskReservationEventType.RELEASE, reservedBefore);
        }
    }

    // ==================== COMMANDS ====================

    public RiskDecision reserve(UUID orderId, BigDecimal amount) {
        RiskState state = riskStatePort.get();
        RiskDecision decision = RiskPolicy.canReserve(state, orderId, amount);

        if (decision.isApproved()) {
            UUID eventId = UUID.randomUUID();
            RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                    eventId.toString(),
                    orderId,
                    amount
            );

            RiskState newState = reducer.reduce(state, event);
            riskStatePort.markEventProcessed(eventId, newState, event);
            logReservation(orderId, RiskReservationEventType.RESERVE, amount);
        }

        return decision;
    }

    public void release(UUID orderId, BigDecimal amount, String reason) {
        UUID eventId = UUID.randomUUID();
        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(
                eventId.toString(),
                orderId,
                amount,
                reason
        );

        RiskState state = riskStatePort.get();
        RiskState newState = reducer.reduce(state, event);
        riskStatePort.markEventProcessed(eventId, newState, event);
        logReservation(orderId, RiskReservationEventType.RELEASE, amount);
    }
    // ==================== STATE MANAGEMENT ====================

    public void syncBalance(BigDecimal actualBalance) {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .balance(MoneyMath.scale(actualBalance))
                .totalEquity(MoneyMath.add(actualBalance, state.getReservedMargin()))
                .build();

        riskStatePort.save(newState);
        log.info("[RISK] Balance synced: {}", actualBalance);
    }

    public void emergencyStop(String reason) {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .halted(true)
                .build();

        riskStatePort.save(newState);
        log.error("[RISK] EMERGENCY STOP: {}", reason);
    }

    public void resumeTrading() {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .halted(false)
                .build();

        riskStatePort.save(newState);
        log.info("[RISK] Trading resumed");
    }

    public void initialize(RiskState state) {
        riskStatePort.save(state);
    }

    public RiskState getState() {
        return riskStatePort.get();
    }

    // ==================== INTERNAL ====================

    private void logReservation(UUID orderId, RiskReservationEventType type, BigDecimal amount) {
        riskReservationLogPort.append(
                new RiskReservationLog(orderId, "N/A", type, amount)
        );
    }

    private BigDecimal calculateQuantity(SignalEvent signal, RiskState state) {
        BigDecimal riskPercent = new BigDecimal("0.01"); // 1% risk
        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0
                || signal.getPrice() == null
                || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.001");
        }

        return baseCapital
                .multiply(riskPercent)
                .divide(signal.getPrice(), 8, RoundingMode.HALF_UP);
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(eventId.getBytes());
        }
    }
}
