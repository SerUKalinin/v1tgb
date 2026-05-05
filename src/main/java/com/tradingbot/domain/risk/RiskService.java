package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.*;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RiskStatePort riskStatePort;
    private final RiskStateReducer reducer;
    private final RiskReservationLogPort riskReservationLogPort;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;

    public RiskService(
            RiskStatePort riskStatePort,
            RiskStateReducer reducer,
            RiskReservationLogPort riskReservationLogPort,
            ExchangeFeasibilityPort feasibilityPort,
            OrderNormalizationService normalizationService
    ) {
        this.riskStatePort = riskStatePort;
        this.reducer = reducer;
        this.riskReservationLogPort = riskReservationLogPort;
        this.feasibilityPort = feasibilityPort;
        this.normalizationService = normalizationService;
    }

    // ==================== MAIN FLOW ====================

    public Optional<Order> evaluateAndReserve(SignalEvent signal) {

        RiskState state = riskStatePort.get();

        if (state.isHalted()) {
            return Optional.empty();
        }

        BigDecimal rawQuantity = calculateQuantity(signal, state);

        NormalizedOrder normalized = normalizationService.normalize(
                new FeasibilityRequest(signal.getSymbol(), rawQuantity, signal.getPrice())
        );

        FeasibilityResult feasibility = feasibilityPort.check(
                new FeasibilityRequest(
                        normalized.getSymbol(),
                        normalized.getQuantity(),
                        normalized.getPrice()
                )
        );

        if (!feasibility.isFeasible()) {
            return Optional.empty();
        }

        BigDecimal requiredCapital =
                normalized.getQuantity().multiply(normalized.getPrice());

        UUID orderId = UUID.randomUUID();

        RiskDecision decision =
                RiskPolicy.canReserve(state, orderId, requiredCapital);

        if (!decision.isApproved()) {
            return Optional.empty();
        }

        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                "RESERVE-" + orderId,
                orderId,
                requiredCapital
        );

        RiskState newState = reducer.reduce(state, event);

        riskStatePort.markEventProcessed(
                parseEventId(event.getEventId()),
                newState,
                event
        );

        logReservation(orderId, RiskReservationEventType.RESERVE, requiredCapital);

        Order order = Order.builder()
                .id(orderId)
                .clientOrderId(ClientOrderIdGenerator.generate(orderId))
                .symbol(signal.getSymbol())
                .side(signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL)
                .type(OrderType.MARKET)
                .originalQuantity(normalized.getQuantity())
                .price(normalized.getPrice())
                .strategyId(signal.getStrategyId())
                .status(OrderStatus.PENDING_EXECUTION)
                .build();

        log.info("[RISK] APPROVED {}", orderId);

        return Optional.of(order);
    }

    // ==================== EVENTS ====================

    public void publish(RiskEvent event) {

        RiskState state = riskStatePort.get();

        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            return;
        }

        UUID eventId = parseEventId(event.getEventId());

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

            RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                    "RESERVE-" + orderId,
                    orderId,
                    amount
            );

            RiskState newState = reducer.reduce(state, event);

            riskStatePort.markEventProcessed(
                    parseEventId(event.getEventId()),
                    newState,
                    event
            );

            logReservation(orderId, RiskReservationEventType.RESERVE, amount);
        }

        return decision;
    }

    public void release(UUID orderId, BigDecimal amount, String reason) {

        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(
                "RELEASE-" + orderId,
                orderId,
                amount,
                reason
        );

        RiskState state = riskStatePort.get();

        RiskState newState = reducer.reduce(state, event);

        riskStatePort.markEventProcessed(
                parseEventId(event.getEventId()),
                newState,
                event
        );

        logReservation(orderId, RiskReservationEventType.RELEASE, amount);
    }

    // ==================== STATE ====================

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

    private void logReservation(UUID orderId,
                                RiskReservationEventType type,
                                BigDecimal amount) {

        riskReservationLogPort.append(
                new RiskReservationLog(
                        orderId,
                        "N/A",
                        type,
                        amount
                )
        );
    }

    private BigDecimal calculateQuantity(SignalEvent signal, RiskState state) {

        BigDecimal riskPercent = new BigDecimal("0.01");

        BigDecimal baseCapital =
                state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0
                || signal.getPrice() == null
                || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {

            return new BigDecimal("0.001");
        }

        return baseCapital
                .multiply(riskPercent)
                .divide(signal.getPrice(), 8, java.math.RoundingMode.HALF_UP);
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(eventId.getBytes());
        }
    }
}