package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Редьюсер состояния risk-engine.
 *
 * <p>Отвечает за чистое (deterministic) применение RiskEvent к RiskState.
 * Не содержит I/O и работает как функциональный трансформер состояния.</p>
 *
 * <p>Основные обязанности:
 * <ul>
 *   <li>обновление баланса и экспозиций</li>
 *   <li>обработка резервов капитала</li>
 *   <li>обработка поступлений капитала после SELL</li>
 *   <li>обработка PnL</li>
 *   <li>автоматический HALT при нарушении рисков</li>
 *   <li>идемпотентность по eventId</li>
 * </ul>
 */
@Slf4j
public class RiskStateReducer {

    public RiskState reduce(
            RiskState currentState,
            RiskEvent event
    ) {
        return reduce(
                currentState,
                event,
                false
        );
    }

    public RiskState reduce(
            RiskState currentState,
            RiskEvent event,
            boolean isReplaying
    ) {

        if (currentState.getProcessedEventIds()
                .contains(event.getEventId())) {

            log.debug(
                    "[RiskReducer] Event {} already processed. Skipping.",
                    event.getEventId()
            );

            return currentState;
        }

        try {
            RiskState newState =
                    switch (event) {

                        case RiskEvent.TradeExecuted e ->
                                handleTradeExecuted(
                                        currentState,
                                        e
                                );

                        case RiskEvent.PriceUpdated e ->
                                handlePriceUpdated(
                                        currentState,
                                        e
                                );

                        case RiskEvent.TradingHalted e ->
                                handleTradingHalted(
                                        currentState,
                                        e
                                );

                        case RiskEvent.CapitalReserved e ->
                                handleCapitalReserved(
                                        currentState,
                                        e
                                );

                        case RiskEvent.CapitalReleased e ->
                                handleCapitalReleased(
                                        currentState,
                                        e
                                );

                        case RiskEvent.CapitalConsumed e ->
                                handleCapitalConsumed(
                                        currentState,
                                        e
                                );

                        case RiskEvent.CapitalCredited e ->
                                handleCapitalCredited(
                                        currentState,
                                        e
                                );

                        default ->
                                currentState;
                    };

            if (!isReplaying) {
                newState.validateInvariants();
            }

            if (!isReplaying && !newState.isHalted()) {
                checkAndApplyAutoHalt(newState);
            }

            Set<String> newEventIds =
                    new HashSet<>(
                            newState.getProcessedEventIds()
                    );

            newEventIds.add(
                    event.getEventId()
            );

            return newState.toBuilder()
                    .processedEventIds(
                            Set.copyOf(newEventIds)
                    )
                    .build();

        } catch (Exception e) {

            log.error(
                    "CRITICAL: RiskStateReducer failed for event {}. Forcing AUTO-HALT.",
                    event.getEventId(),
                    e
            );

            return currentState.toBuilder()
                    .halted(true)
                    .lastError(e.getMessage())
                    .build();
        }
    }

    private void checkAndApplyAutoHalt(
            RiskState state
    ) {

        BigDecimal dailyLossLimit =
                MoneyMath.multiply(
                        state.getTotalEquity(),
                        new BigDecimal("0.05")
                );

        if (MoneyMath.isLess(
                state.getDailyPnl(),
                dailyLossLimit.negate()
        )) {

            log.error(
                    "[RiskReducer] AUTO-HALT: Daily loss limit exceeded (5%)"
            );

            state.setHalted(true);
            return;
        }

        BigDecimal currentDrawdown =
                calculateDrawdown(state);

        if (MoneyMath.isGreater(
                currentDrawdown,
                new BigDecimal("10.0")
        )) {

            log.error(
                    "[RiskReducer] AUTO-HALT: Max drawdown exceeded (10%). Current DD: {}%",
                    currentDrawdown
            );

            state.setHalted(true);
        }
    }

    private BigDecimal calculateDrawdown(
            RiskState state
    ) {

        if (MoneyMath.isZero(state.getMaxEquity())
                || MoneyMath.isLess(
                state.getMaxEquity(),
                BigDecimal.ZERO
        )) {
            return BigDecimal.ZERO;
        }

        BigDecimal diff =
                MoneyMath.subtract(
                        state.getMaxEquity(),
                        state.getTotalEquity()
                );

        return MoneyMath.multiply(
                MoneyMath.divide(
                        diff,
                        state.getMaxEquity()
                ),
                new BigDecimal("100")
        );
    }

    private RiskState handleTradingHalted(
            RiskState state,
            RiskEvent.TradingHalted event
    ) {

        log.warn(
                "Risk Engine HALTED: {}",
                event.reason()
        );

        return state.toBuilder()
                .halted(true)
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handleTradeExecuted(
            RiskState state,
            RiskEvent.TradeExecuted event
    ) {

        BigDecimal newDailyPnl =
                MoneyMath.add(
                        state.getDailyPnl(),
                        event.realizedPnl()
                );

        BigDecimal newEquity =
                MoneyMath.add(
                        state.getTotalEquity(),
                        event.realizedPnl()
                );

        BigDecimal newMaxEquity =
                newEquity.max(
                        state.getMaxEquity()
                );

        Map<String, BigDecimal> newExposures =
                new HashMap<>(
                        state.getSymbolExposures()
                );

        BigDecimal currentExp =
                newExposures.getOrDefault(
                        event.symbol(),
                        BigDecimal.ZERO
                );

        BigDecimal tradeValue =
                MoneyMath.multiply(
                        event.quantity(),
                        event.price()
                );

        newExposures.put(
                event.symbol(),
                MoneyMath.add(
                        currentExp,
                        tradeValue
                )
        );

        return state.toBuilder()
                .dailyPnl(newDailyPnl)
                .totalEquity(newEquity)
                .maxEquity(newMaxEquity)
                .symbolExposures(
                        Map.copyOf(newExposures)
                )
                .lastUpdateTimestamp(
                        event.timestamp()
                )
                .build();
    }

    private RiskState handlePriceUpdated(
            RiskState state,
            RiskEvent.PriceUpdated event
    ) {

        return state.toBuilder()
                .lastUpdateTimestamp(
                        event.timestamp()
                )
                .build();
    }

    private RiskState handleCapitalReserved(
            RiskState state,
            RiskEvent.CapitalReserved event
    ) {

        if (state.getActiveReservations()
                .containsKey(event.orderId())) {

            log.warn(
                    "[RiskReducer] Idempotency: Order {} already has active reservation. No-op.",
                    event.orderId()
            );

            return state;
        }

        if (event.amount() == null
                || event.amount().signum() <= 0) {

            throw new IllegalArgumentException(
                    "Capital reservation amount must be positive"
            );
        }

        log.info(
                "[RiskReducer] Reserving {} for order {}",
                event.amount(),
                event.orderId()
        );

        Map<UUID, BigDecimal> newReservations =
                new HashMap<>(
                        state.getActiveReservations()
                );

        newReservations.put(
                event.orderId(),
                event.amount()
        );

        return state.toBuilder()
                .balance(
                        MoneyMath.subtract(
                                state.getBalance(),
                                event.amount()
                        )
                )
                .activeReservations(
                        Map.copyOf(newReservations)
                )
                .lastUpdateTimestamp(
                        event.timestamp()
                )
                .build();
    }

    private RiskState handleCapitalReleased(
            RiskState state,
            RiskEvent.CapitalReleased event
    ) {

        BigDecimal reservedAmount =
                state.getActiveReservations()
                        .get(event.orderId());

        if (reservedAmount == null) {

            log.warn(
                    "[RiskReducer] Idempotency: No active reservation for order {}. Release ignored.",
                    event.orderId()
            );

            return state;
        }

        BigDecimal amountToRelease =
                (
                        event.amount() == null
                                || MoneyMath.isZero(
                                event.amount()
                        )
                )
                        ? reservedAmount
                        : event.amount();

        if (amountToRelease.signum() < 0) {
            throw new IllegalArgumentException(
                    "Capital release amount must not be negative"
            );
        }

        if (amountToRelease.compareTo(reservedAmount) > 0) {
            throw new IllegalArgumentException(
                    "Capital release amount exceeds active reservation. " +
                            "orderId=" + event.orderId() +
                            ", reserved=" + reservedAmount +
                            ", release=" + amountToRelease
            );
        }

        log.info(
                "[RiskReducer] Releasing {} for order {} (Reason: {})",
                amountToRelease,
                event.orderId(),
                event.reason()
        );

        Map<UUID, BigDecimal> newReservations =
                new HashMap<>(
                        state.getActiveReservations()
                );

        BigDecimal remainingReservation =
                reservedAmount.subtract(
                        amountToRelease
                );

        if (MoneyMath.isZero(remainingReservation)) {
            newReservations.remove(
                    event.orderId()
            );
        } else {
            newReservations.put(
                    event.orderId(),
                    remainingReservation
            );
        }

        return state.toBuilder()
                .balance(
                        MoneyMath.add(
                                state.getBalance(),
                                amountToRelease
                        )
                )
                .activeReservations(
                        Map.copyOf(newReservations)
                )
                .lastUpdateTimestamp(
                        event.timestamp()
                )
                .build();
    }

    /**
     * Settlement BUY execution.
     *
     * <p>
     * Важная семантика:
     *
     * <ul>
     *     <li>FILLED: reservation закрывается полностью;</li>
     *     <li>PARTIAL FILL: фактически исполненная часть потребляется,
     *         остаток reservation сохраняется;</li>
     *     <li>если actual > reserved: дополнительный расход списывается
     *         из доступного balance;</li>
     *     <li>если actual < reserved: balance не меняется,
     *         reservation уменьшается на фактически исполненную сумму.</li>
     * </ul>
     */
    private RiskState handleCapitalConsumed(
            RiskState state,
            RiskEvent.CapitalConsumed event
    ) {

        BigDecimal reservedAmount =
                state.getActiveReservations()
                        .get(event.orderId());

        if (reservedAmount == null) {

            log.warn(
                    "[RiskReducer] Idempotency: No active reservation for order {}. Consume ignored.",
                    event.orderId()
            );

            return state;
        }

        if (event.amount() == null
                || event.amount().signum() <= 0) {

            throw new IllegalArgumentException(
                    "Capital consumed amount must be positive"
            );
        }

        BigDecimal actualExecutedNotional =
                event.amount();

        BigDecimal settlementDifference =
                actualExecutedNotional.subtract(
                        reservedAmount
                );

        log.info(
                "[RiskReducer] Consuming BUY reservation for order {}: " +
                        "reserved={}, actual={}, difference={}",
                event.orderId(),
                reservedAmount,
                actualExecutedNotional,
                settlementDifference
        );

        Map<UUID, BigDecimal> newReservations =
                new HashMap<>(
                        state.getActiveReservations()
                );

        BigDecimal newBalance =
                state.getBalance();

        if (settlementDifference.signum() < 0) {

            /*
             * Partial fill:
             *
             * reserved = 100
             * actual   = 30
             * remaining reservation = 70
             *
             * balance remains 9900,
             * reservation becomes 70.
             */
            BigDecimal remainingReservation =
                    reservedAmount.subtract(
                            actualExecutedNotional
                    );

            if (remainingReservation.signum() <= 0) {
                throw new IllegalStateException(
                        "Remaining reservation must be positive for partial consume"
                );
            }

            newReservations.put(
                    event.orderId(),
                    remainingReservation
            );

        } else if (settlementDifference.signum() > 0) {

            /*
             * Actual execution exceeded reserved amount.
             * The active reservation is fully consumed and the extra
             * notional is additionally taken from available balance.
             */
            newReservations.remove(
                    event.orderId()
            );

            newBalance =
                    MoneyMath.subtract(
                            state.getBalance(),
                            settlementDifference
                    );

        } else {

            /*
             * Exact settlement:
             * reserved == actual.
             */
            newReservations.remove(
                    event.orderId()
            );
        }

        return state.toBuilder()
                .balance(newBalance)
                .activeReservations(
                        Map.copyOf(newReservations)
                )
                .lastUpdateTimestamp(
                        event.timestamp()
                )
                .build();
    }

    private RiskState handleCapitalCredited(
            RiskState state,
            RiskEvent.CapitalCredited event
    ) {

        if (event.amount() == null
                || event.amount().signum() <= 0) {

            throw new IllegalArgumentException(
                    "Capital credited amount must be positive"
            );
        }

        log.info(
                "[RiskReducer] Crediting {} for order {} (Reason: {})",
                event.amount(),
                event.orderId(),
                event.reason()
        );

        BigDecimal newBalance =
                MoneyMath.add(
                        state.getBalance(),
                        event.amount()
                );

        BigDecimal newTotalEquity =
                MoneyMath.add(
                        state.getTotalEquity(),
                        event.amount()
                );

        BigDecimal newMaxEquity =
                newTotalEquity.max(
                        state.getMaxEquity()
                );

        return state.toBuilder()
                .balance(newBalance)
                .totalEquity(newTotalEquity)
                .maxEquity(newMaxEquity)
                .lastUpdateTimestamp(
                        event.timestamp()
                )
                .build();
    }
}