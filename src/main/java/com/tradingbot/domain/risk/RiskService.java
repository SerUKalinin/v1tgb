package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.*;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.position.PositionAvailabilityPort;
import com.tradingbot.tracing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервис управления рисками.
 *
 * <p>
 * Доменный сервис не зависит от Spring, JPA или infrastructure.
 * Транзакционные границы задаются application-слоем.
 */
public class RiskService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    RiskService.class
            );

    private final RiskStatePort riskStatePort;
    private final RiskStateReducer reducer;
    private final RiskReservationLogPort riskReservationLogPort;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;
    private final ExecutionLogger executionLogger;
    private final PositionAvailabilityPort positionAvailabilityPort;

    public RiskService(
            RiskStatePort riskStatePort,
            RiskStateReducer reducer,
            RiskReservationLogPort riskReservationLogPort,
            ExchangeFeasibilityPort feasibilityPort,
            OrderNormalizationService normalizationService,
            ExecutionLogger executionLogger,
            PositionAvailabilityPort positionAvailabilityPort
    ) {
        this.riskStatePort = riskStatePort;
        this.reducer = reducer;
        this.riskReservationLogPort = riskReservationLogPort;
        this.feasibilityPort = feasibilityPort;
        this.normalizationService = normalizationService;
        this.executionLogger = executionLogger;
        this.positionAvailabilityPort = positionAvailabilityPort;
    }

    // ==================== MAIN FLOW ====================

    public Optional<Order> evaluateSignal(
            ExecutionContext context,
            SignalEvent signal
    ) {

        IdentityContext identity =
                context.identity();

        log.info(
                "[TRACE_FLOW] ENTER RiskService.evaluateSignal for identity: {}",
                identity
        );

        RiskState state =
                riskStatePort.get();

        log.info(
                "[TRACE_FLOW] Current RiskState: halted={}, balance={}, reserved={}",
                state.isHalted(),
                state.getBalance(),
                state.getReservedMargin()
        );

        if (state.isHalted()) {

            log.warn(
                    "[TRACE_FLOW] EXIT RiskService - REJECTED: " +
                            "System is HALTED for identity: {}",
                    identity
            );

            return Optional.empty();
        }

        BigDecimal rawQuantity =
                calculateQuantity(
                        signal,
                        state
                );

        log.info(
                "[TRACE_FLOW] Calculated raw quantity: {} for identity: {}",
                rawQuantity,
                identity
        );

        NormalizedOrder normalized =
                normalizationService.normalize(
                        new FeasibilityRequest(
                                signal.getSymbol(),
                                rawQuantity,
                                signal.getPrice()
                        )
                );

        log.info(
                "[TRACE_FLOW] Normalized order: qty={}, price={} for identity: {}",
                normalized.getQuantity(),
                normalized.getPrice(),
                identity
        );

        /*
         * SELL работает только при наличии достаточной позиции.
         *
         * SELL не резервирует quote capital.
         */
        if (signal.getType() == SignalType.SELL) {

            BigDecimal availableQuantity =
                    positionAvailabilityPort.getAvailableQuantity(
                            signal.getSymbol(),
                            signal.getStrategyId()
                    );

            log.info(
                    "[TRACE_FLOW] SELL position check: " +
                            "symbol={}, strategyId={}, requestedQty={}, availableQty={}",
                    signal.getSymbol(),
                    signal.getStrategyId(),
                    normalized.getQuantity(),
                    availableQuantity
            );

            if (availableQuantity.compareTo(
                    normalized.getQuantity()
            ) < 0) {

                log.warn(
                        "[TRACE_FLOW] EXIT RiskService.evaluateSignal - " +
                                "REJECTED: SELL exceeds available position. " +
                                "symbol={}, strategyId={}, requestedQty={}, availableQty={}",
                        signal.getSymbol(),
                        signal.getStrategyId(),
                        normalized.getQuantity(),
                        availableQuantity
                );

                return Optional.empty();
            }
        }

        FeasibilityResult feasibility =
                feasibilityPort.check(
                        new FeasibilityRequest(
                                normalized.getSymbol(),
                                normalized.getQuantity(),
                                normalized.getPrice()
                        )
                );

        log.info(
                "[TRACE_FLOW] Feasibility check: feasible={}, reason={} for identity: {}",
                feasibility.isFeasible(),
                feasibility.getReason(),
                identity
        );

        if (!feasibility.isFeasible()) {

            log.warn(
                    "[TRACE_FLOW] EXIT RiskService - REJECTED: " +
                            "Not feasible. Reason: {} for identity: {}",
                    feasibility.getReason(),
                    identity
            );

            return Optional.empty();
        }

        UUID orderId =
                IdentityFactory.deriveOrder(
                        signal.getSignalId()
                );

        /*
         * Только BUY резервирует quote capital.
         */
        if (signal.getType() == SignalType.BUY) {

            BigDecimal requiredCapital =
                    normalized.getQuantity()
                            .multiply(
                                    normalized.getPrice()
                            );

            RiskDecision decision =
                    RiskPolicy.canReserve(
                            state,
                            orderId,
                            requiredCapital
                    );

            log.info(
                    "[TRACE_FLOW] BUY RiskPolicy decision: " +
                            "approved={}, reason={} for identity: {}",
                    decision.isApproved(),
                    decision.getReason(),
                    identity
            );

            if (!decision.isApproved()) {

                log.warn(
                        "[TRACE_FLOW] EXIT RiskService - REJECTED: " +
                                "Policy violation. Reason: {} for identity: {}",
                        decision.getReason(),
                        identity
                );

                return Optional.empty();
            }

            UUID eventId =
                    IdentityFactory.deriveEventId(
                            orderId,
                            "capital-reserved"
                    );

            RiskEvent.CapitalReserved event =
                    new RiskEvent.CapitalReserved(
                            eventId.toString(),
                            orderId,
                            requiredCapital
                    );

            RiskState newState =
                    reducer.reduce(
                            state,
                            event
                    );

            log.info(
                    "[TRACE_FLOW] Persisting BUY risk reservation for identity: {}",
                    identity
            );

            riskStatePort.markEventProcessed(
                    eventId,
                    newState,
                    event
            );

            log.info(
                    "[TRACE_FLOW] BUY risk reservation persisted for identity: {}",
                    identity
            );

            logReservation(
                    orderId,
                    RiskReservationEventType.RESERVE,
                    requiredCapital,
                    eventId
            );

        } else {

            log.info(
                    "[TRACE_FLOW] SELL does not reserve quote capital: " +
                            "orderId={}, symbol={}, quantity={}, price={}",
                    orderId,
                    normalized.getSymbol(),
                    normalized.getQuantity(),
                    normalized.getPrice()
            );
        }

        Order order =
                Order.createPendingExecution(
                        orderId,
                        ClientOrderIdGenerator.generate(orderId),
                        signal.getSymbol(),
                        signal.getType() == SignalType.BUY
                                ? OrderSide.BUY
                                : OrderSide.SELL,
                        OrderType.MARKET,
                        normalized.getQuantity(),
                        normalized.getPrice(),
                        signal.getStrategyId(),
                        signal.getSignalId()
                );

        log.info(
                "[TRACE_FLOW] EXIT RiskService.evaluateSignal - " +
                        "APPROVED for identity: {}",
                identity
        );

        return Optional.of(order);
    }

    // ==================== EVENTS ====================

    public void publish(
            RiskEvent event
    ) {

        RiskState state =
                riskStatePort.get();

        if (state.isHalted()
                && !(event instanceof RiskEvent.TradingHalted)) {

            return;
        }

        UUID eventId =
                parseEventId(
                        event.getEventId()
                );

        if (riskStatePort.isEventProcessed(eventId)) {
            return;
        }

        BigDecimal reservedBefore = null;

        if (event instanceof RiskEvent.CapitalReleased e) {

            reservedBefore =
                    state.getActiveReservations()
                            .get(e.orderId());

        } else if (event instanceof RiskEvent.CapitalConsumed e) {

            reservedBefore =
                    state.getActiveReservations()
                            .get(e.orderId());
        }

        RiskState newState =
                reducer.reduce(
                        state,
                        event
                );

        riskStatePort.markEventProcessed(
                eventId,
                newState,
                event
        );

        if (event instanceof RiskEvent.CapitalReserved e) {

            logReservation(
                    e.orderId(),
                    RiskReservationEventType.RESERVE,
                    e.amount(),
                    eventId
            );
        }

        if (event instanceof RiskEvent.CapitalReleased e
                && reservedBefore != null) {

            logReservation(
                    e.orderId(),
                    RiskReservationEventType.RELEASE,
                    reservedBefore,
                    eventId
            );
        }

        if (event instanceof RiskEvent.CapitalConsumed e
                && reservedBefore != null) {

            logReservation(
                    e.orderId(),
                    RiskReservationEventType.CONSUME,
                    reservedBefore,
                    eventId
            );
        }
    }

    // ==================== COMMANDS ====================

    public RiskDecision reserve(
            ExecutionContext context,
            BigDecimal amount
    ) {

        UUID orderId =
                UUID.fromString(
                        context.business().orderId()
                );

        RiskState state =
                riskStatePort.get();

        RiskDecision decision =
                RiskPolicy.canReserve(
                        state,
                        orderId,
                        amount
                );

        if (decision.isApproved()) {

            UUID eventId =
                    IdentityFactory.deriveEventId(
                            orderId,
                            "capital-reserved-"
                                    + context.attempt()
                                    .attemptNumber()
                    );

            RiskEvent.CapitalReserved event =
                    new RiskEvent.CapitalReserved(
                            eventId.toString(),
                            orderId,
                            amount
                    );

            RiskState newState =
                    reducer.reduce(
                            state,
                            event
                    );

            riskStatePort.markEventProcessed(
                    eventId,
                    newState,
                    event
            );

            logReservation(
                    orderId,
                    RiskReservationEventType.RESERVE,
                    amount,
                    eventId
            );

            log.info(
                    "[RISK] Reserved {} for order {}",
                    amount,
                    orderId
            );
        }

        return decision;
    }

    public void release(
            ExecutionContext context,
            BigDecimal amount,
            String reason
    ) {

        UUID orderId =
                UUID.fromString(
                        context.business().orderId()
                );

        RiskState state =
                riskStatePort.get();

        UUID eventId =
                IdentityFactory.deriveEventId(
                        orderId,
                        "capital-released-"
                                + Instant.now().toEpochMilli()
                );

        RiskEvent.CapitalReleased event =
                new RiskEvent.CapitalReleased(
                        eventId.toString(),
                        orderId,
                        amount,
                        reason
                );

        RiskState newState =
                reducer.reduce(
                        state,
                        event
                );

        riskStatePort.markEventProcessed(
                eventId,
                newState,
                event
        );

        logReservation(
                orderId,
                RiskReservationEventType.RELEASE,
                amount,
                eventId
        );

        log.info(
                "[RISK] Released {} for order {}, reason: {}",
                amount,
                orderId,
                reason
        );
    }

    /**
     * Existing execution settlement.
     *
     * <p>
     * Kept for the normal execution path.
     */
    public void consumeReservation(
            ExecutionContext context,
            BigDecimal executedNotional,
            String reason
    ) {

        consumeReservation(
                context,
                executedNotional,
                reason,
                "execution-result"
        );
    }

    /**
     * Incremental/cumulative-aware settlement.
     *
     * <p>
     * settlementKey is part of event identity.
     */
    public void consumeReservation(
            ExecutionContext context,
            BigDecimal executedNotional,
            String reason,
            String settlementKey
    ) {

        UUID orderId =
                UUID.fromString(
                        context.business().orderId()
                );

        if (executedNotional == null
                || executedNotional.signum() <= 0) {

            throw new IllegalArgumentException(
                    "Executed BUY notional must be positive"
            );
        }

        if (settlementKey == null
                || settlementKey.isBlank()) {

            throw new IllegalArgumentException(
                    "Settlement key must not be blank"
            );
        }

        RiskState state =
                riskStatePort.get();

        BigDecimal reservedAmount =
                state.getActiveReservations()
                        .get(orderId);

        if (reservedAmount == null) {

            log.warn(
                    "[RISK] No active reservation to consume for order {}",
                    orderId
            );

            return;
        }

        UUID executionId =
                context.attempt().executionId();

        UUID eventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "capital-consumed:"
                                + settlementKey
                );

        /*
         * Deterministic idempotency barrier.
         *
         * Повторная обработка одного cumulative checkpoint
         * не должна второй раз менять RiskState
         * и не должна второй раз писать audit log.
         */
        if (riskStatePort.isEventProcessed(eventId)) {

            log.info(
                    "[RISK-IDEMPOTENT-SKIP] Capital settlement " +
                            "already processed. orderId={}, key={}",
                    orderId,
                    settlementKey
            );

            return;
        }

        RiskEvent.CapitalConsumed event =
                new RiskEvent.CapitalConsumed(
                        eventId.toString(),
                        orderId,
                        executedNotional,
                        reason
                );

        RiskState newState =
                reducer.reduce(
                        state,
                        event
                );

        riskStatePort.markEventProcessed(
                eventId,
                newState,
                event
        );

        logReservation(
                orderId,
                RiskReservationEventType.CONSUME,
                executedNotional,
                eventId
        );

        log.info(
                "[RISK] Settled BUY execution delta {} for order {}. " +
                        "Reserved={} Delta={} Key={}",
                executedNotional,
                orderId,
                reservedAmount,
                executedNotional,
                settlementKey
        );
    }

    public void emergencyStop(
            String reason
    ) {

        RiskState state =
                riskStatePort.get();

        RiskState newState =
                state.toBuilder()
                        .halted(true)
                        .build();

        riskStatePort.save(
                newState
        );

        log.error(
                "[RISK] EMERGENCY STOP: {}",
                reason
        );
    }

    public void resumeTrading() {

        RiskState state =
                riskStatePort.get();

        RiskState newState =
                state.toBuilder()
                        .halted(false)
                        .build();

        riskStatePort.save(
                newState
        );

        log.info(
                "[RISK] Trading resumed"
        );
    }

    public void initialize(
            RiskState state
    ) {
        riskStatePort.save(
                state
        );
    }

    public void syncBalance(
            BigDecimal actualBalance
    ) {

        RiskState state =
                riskStatePort.get();

        RiskState newState =
                state.toBuilder()
                        .balance(actualBalance)
                        .build();

        riskStatePort.save(
                newState
        );

        log.info(
                "[RISK] Balance synchronized to: {}",
                actualBalance
        );
    }

    public RiskState getState() {
        return riskStatePort.get();
    }

    // ==================== INTERNAL ====================

    private void logReservation(
            UUID orderId,
            RiskReservationEventType type,
            BigDecimal amount,
            UUID eventId
    ) {

        riskReservationLogPort.append(
                new RiskReservationLog(
                        orderId,
                        "N/A",
                        type,
                        amount,
                        eventId
                )
        );
    }

    private BigDecimal calculateQuantity(
            SignalEvent signal,
            RiskState state
    ) {

        BigDecimal riskPercent =
                new BigDecimal("0.01");

        BigDecimal baseCapital =
                state.getTotalEquity()
                        .max(
                                state.getBalance()
                        );

        if (baseCapital.compareTo(
                BigDecimal.ZERO
        ) <= 0
                || signal.getPrice() == null
                || signal.getPrice().compareTo(
                BigDecimal.ZERO
        ) <= 0) {

            return new BigDecimal("0.001");
        }

        return baseCapital
                .multiply(
                        riskPercent
                )
                .divide(
                        signal.getPrice(),
                        8,
                        RoundingMode.HALF_UP
                );
    }

    private UUID parseEventId(
            String eventId
    ) {

        try {

            return UUID.fromString(
                    eventId
            );

        } catch (Exception e) {

            return UUID.nameUUIDFromBytes(
                    eventId.getBytes()
            );
        }
    }
}