package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

import static com.tradingbot.domain.risk.RiskState.safeCompare;

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
 *   <li>обработка PnL</li>
 *   <li>автоматический HALT при нарушении рисков</li>
 *   <li>идемпотентность по eventId</li>
 * </ul>
 * </p>
 */
@Slf4j
public class RiskStateReducer {

    /**
     * Применяет событие к текущему состоянию risk-engine.
     */
    public RiskState reduce(RiskState currentState, RiskEvent event) {
        return reduce(currentState, event, false);
    }

    /**
     * Применяет событие к состоянию risk-engine.
     *
     * @param isReplaying флаг режима восстановления (replay mode)
     */
    public RiskState reduce(RiskState currentState, RiskEvent event, boolean isReplaying) {

        // 1. Идемпотентность по eventId
        if (currentState.getProcessedEventIds().contains(event.getEventId())) {
            log.debug("[RiskReducer] Event {} already processed. Skipping.", event.getEventId());
            return currentState;
        }

        try {
            // 2. Диспетчеризация событий
            RiskState newState = switch (event) {
                case RiskEvent.TradeExecuted e -> handleTradeExecuted(currentState, e);
                case RiskEvent.PriceUpdated e -> handlePriceUpdated(currentState, e);
                case RiskEvent.TradingHalted e -> handleTradingHalted(currentState, e);
                case RiskEvent.CapitalReserved e -> handleCapitalReserved(currentState, e);
                case RiskEvent.CapitalReleased e -> handleCapitalReleased(currentState, e);
                default -> currentState;
            };

            // 3. Проверка инвариантов
            if (!isReplaying) {
                newState.validateInvariants();
            }

            // 4. Auto-HALT логика
            if (!isReplaying && !newState.isHalted()) {
                checkAndApplyAutoHalt(newState);
            }

            // 5. Обновление списка обработанных событий
            Set<String> newEventIds = new HashSet<>(newState.getProcessedEventIds());
            newEventIds.add(event.getEventId());

            return newState.toBuilder()
                    .processedEventIds(Set.copyOf(newEventIds))
                    .build();

        } catch (Exception e) {
            log.error("CRITICAL: RiskStateReducer failed for event {}. Forcing AUTO-HALT.",
                    event.getEventId(), e);

            return currentState.toBuilder()
                    .halted(true)
                    .lastError(e.getMessage())
                    .build();
        }
    }

    /**
     * Auto-halt проверка лимитов риска.
     */
    private void checkAndApplyAutoHalt(RiskState state) {

        // Дневной убыток > 5%
        BigDecimal dailyLossLimit = MoneyMath.multiply(state.getTotalEquity(), new BigDecimal("0.05"));

        if (MoneyMath.isLess(state.getDailyPnl(), dailyLossLimit.negate())) {
            log.error("[RiskReducer] AUTO-HALT: Daily loss limit exceeded (5%)");
            state.setHalted(true);
            return;
        }

        // Просадка > 10%
        BigDecimal currentDrawdown = calculateDrawdown(state);

        if (MoneyMath.isGreater(currentDrawdown, new BigDecimal("10.0"))) {
            log.error("[RiskReducer] AUTO-HALT: Max drawdown exceeded (10%). Current DD: {}%", currentDrawdown);
            state.setHalted(true);
        }
    }

    private BigDecimal calculateDrawdown(RiskState state) {
        if (MoneyMath.isZero(state.getMaxEquity()) || MoneyMath.isLess(state.getMaxEquity(), BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }

        BigDecimal diff = MoneyMath.subtract(state.getMaxEquity(), state.getTotalEquity());

        return MoneyMath.multiply(
                MoneyMath.divide(diff, state.getMaxEquity()),
                new BigDecimal("100")
        );
    }

    /**
     * Обработка события остановки торговли.
     */
    private RiskState handleTradingHalted(RiskState state, RiskEvent.TradingHalted event) {
        log.warn("Risk Engine HALTED: {}", event.reason());

        return state.toBuilder()
                .halted(true)
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handleTradeExecuted(RiskState state, RiskEvent.TradeExecuted event) {

        BigDecimal newDailyPnl = MoneyMath.add(state.getDailyPnl(), event.realizedPnl());
        BigDecimal newEquity = MoneyMath.add(state.getTotalEquity(), event.realizedPnl());
        BigDecimal newMaxEquity = newEquity.max(state.getMaxEquity());

        Map<String, BigDecimal> newExposures = new HashMap<>(state.getSymbolExposures());
        BigDecimal currentExp = newExposures.getOrDefault(event.symbol(), BigDecimal.ZERO);
        BigDecimal tradeValue = MoneyMath.multiply(event.quantity(), event.price());
        newExposures.put(event.symbol(), MoneyMath.add(currentExp, tradeValue));

        return state.toBuilder()
                .dailyPnl(newDailyPnl)
                .totalEquity(newEquity)
                .maxEquity(newMaxEquity)
                .symbolExposures(Map.copyOf(newExposures))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handlePriceUpdated(RiskState state, RiskEvent.PriceUpdated event) {
        return state.toBuilder()
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handleCapitalReserved(RiskState state, RiskEvent.CapitalReserved event) {

        if (state.getActiveReservations().containsKey(event.orderId())) {
            log.warn("[RiskReducer] Idempotency: Order {} already has active reservation. No-op.", event.orderId());
            return state;
        }

        log.info("[RiskReducer] Reserving {} for order {}", event.amount(), event.orderId());

        Map<UUID, BigDecimal> newReservations = new HashMap<>(state.getActiveReservations());
        newReservations.put(event.orderId(), event.amount());

        return state.toBuilder()
                .balance(MoneyMath.subtract(state.getBalance(), event.amount()))
                .activeReservations(Map.copyOf(newReservations))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }

    private RiskState handleCapitalReleased(RiskState state, RiskEvent.CapitalReleased event) {

        BigDecimal reservedAmount = state.getActiveReservations().get(event.orderId());

        if (reservedAmount == null) {
            log.warn("[RiskReducer] Idempotency: No active reservation for order {}. Release ignored.", event.orderId());
            return state;
        }

        BigDecimal amountToRelease =
                (event.amount() == null || MoneyMath.isZero(event.amount()))
                        ? reservedAmount
                        : event.amount();

        log.info("[RiskReducer] Releasing {} for order {} (Reason: {})",
                amountToRelease, event.orderId(), event.reason());

        Map<UUID, BigDecimal> newReservations = new HashMap<>(state.getActiveReservations());
        newReservations.remove(event.orderId());

        return state.toBuilder()
                .balance(MoneyMath.add(state.getBalance(), amountToRelease))
                .activeReservations(Map.copyOf(newReservations))
                .lastUpdateTimestamp(event.timestamp())
                .build();
    }
}