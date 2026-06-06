package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.*;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public Optional<Order> evaluateSignal(ExecutionContext context, SignalEvent signal) {
        IdentityContext identity = context.identity();
        log.info("[TRACE_FLOW] ENTER RiskService.evaluateSignal for identity: {}", identity);
        log.info("[TRACE_FLOW] Current RiskState: halted={}, balance={}, reserved={}",
                riskStatePort.get().isHalted(), riskStatePort.get().getBalance(), riskStatePort.get().getReservedMargin());

        RiskState state = riskStatePort.get();
        if (state.isHalted()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: System is HALTED for identity: {}", identity);
            return Optional.empty();
        }

        // 1. Расчет объема
        BigDecimal rawQuantity = calculateQuantity(signal, state);
        log.info("[TRACE_FLOW] Calculated raw quantity: {} for identity: {}", rawQuantity, identity);

        // 2. Нормализация под требования биржи
        NormalizedOrder normalized = normalizationService.normalize(
                new FeasibilityRequest(signal.getSymbol(), rawQuantity, signal.getPrice())
        );
        log.info("[TRACE_FLOW] Normalized order: qty={}, price={} for identity: {}", normalized.getQuantity(), normalized.getPrice(), identity);

        // 3. Проверка возможности исполнения (Feasibility)
        FeasibilityResult feasibility = feasibilityPort.check(
                new FeasibilityRequest(
                        normalized.getSymbol(),
                        normalized.getQuantity(),
                        normalized.getPrice()
                )
        );
        log.info("[TRACE_FLOW] Feasibility check: feasible={}, reason={} for identity: {}", feasibility.isFeasible(), feasibility.getReason(), identity);

        if (!feasibility.isFeasible()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: Not feasible. Reason: {} for identity: {}", feasibility.getReason(), identity);
            return Optional.empty();
        }

        // 4. Проверка лимитов капитала (Risk Policy)
        BigDecimal requiredCapital = normalized.getQuantity().multiply(normalized.getPrice());
        UUID orderId = IdentityFactory.deriveOrder(signal.getSignalId());

        RiskDecision decision = RiskPolicy.canReserve(state, orderId, requiredCapital);
        log.info("[TRACE_FLOW] RiskPolicy decision: approved={}, reason={} for identity: {}", decision.isApproved(), decision.getReason(), identity);

        if (!decision.isApproved()) {
            log.warn("[TRACE_FLOW] EXIT RiskService - REJECTED: Policy violation. Reason: {} for identity: {}", decision.getReason(), identity);
            return Optional.empty();
        }

        // 5. Резервирование капитала
        UUID eventId = IdentityFactory.deriveEventId(orderId, "capital-reserved");
        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                eventId.toString(),
                orderId,
                requiredCapital
        );

        RiskState newState = reducer.reduce(state, event);

        log.info("[TRACE_FLOW] Persisting risk state change for identity: {}", identity);
        riskStatePort.markEventProcessed(
                eventId,
                newState,
                event
        );
        log.info("[TRACE_FLOW] Risk state persisted for identity: {}", identity);

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
        log.info("[TRACE_FLOW] EXIT RiskService.evaluateSignal - APPROVED for identity: {}", identity);

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

    public RiskDecision reserve(ExecutionContext context, BigDecimal amount) {
        UUID orderId = UUID.fromString(context.business().orderId());
        RiskState state = riskStatePort.get();
        RiskDecision decision = RiskPolicy.canReserve(state, orderId, amount);

        if (decision.isApproved()) {
            UUID eventId = IdentityFactory.deriveEventId(orderId, "capital-reserved-" + context.attempt().attemptNumber());
            RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(                    eventId.toString(),
                    orderId,
                    amount
            );

            RiskState newState = reducer.reduce(state, event);
            riskStatePort.markEventProcessed(eventId, newState, event);
            logReservation(orderId, RiskReservationEventType.RESERVE, amount);
            log.info("[RISK] Capital reserved for order: {}", orderId);
        }

        return decision;
    }

    public void release(ExecutionContext context, BigDecimal amount, String reason) {
        UUID orderId = UUID.fromString(context.business().orderId());
        UUID eventId = IdentityFactory.deriveEventId(orderId, "capital-released-" + context.attempt().attemptNumber());
        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(                eventId.toString(),
                orderId,
                amount,
                reason
        );

        RiskState state = riskStatePort.get();
        RiskState newState = reducer.reduce(state, event);
        riskStatePort.markEventProcessed(eventId, newState, event);
        logReservation(orderId, RiskReservationEventType.RELEASE, amount);
        log.info("[RISK] Capital released for order: {}. Reason: {}", orderId, reason);
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

    public void syncBalance(BigDecimal actualBalance) {
        RiskState state = riskStatePort.get();
        RiskState newState = state.toBuilder()
                .balance(actualBalance)
                .build();
        riskStatePort.save(newState);
        log.info("[RISK] Balance synchronized to: {}", actualBalance);
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
