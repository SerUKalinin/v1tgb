package com.tradingbot.domain.position;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Иммутабельное состояние торговой позиции.
 * <p>
 * Представляет снимок (snapshot) позиции в конкретный момент времени.
 * Используется в event-driven модели для детерминированного пересчёта состояния
 * через PositionReducer.
 */
public record PositionState(

        /**
         * Торговый символ (инструмент).
         */
        String symbol,

        /**
         * Идентификатор стратегии, управляющей позицией.
         */
        String strategyId,

        /**
         * Текущий чистый объём позиции (net exposure).
         */
        BigDecimal netQuantity,

        /**
         * Средняя цена входа в позицию.
         */
        BigDecimal averagePrice,

        /**
         * Идентификатор последней применённой сделки.
         */
        UUID lastTradeId,

        /**
         * Реализованный PnL по позиции.
         */
        BigDecimal realizedPnl,

        /**
         * Уровень stop-loss.
         */
        BigDecimal stopLoss,

        /**
         * Уровень take-profit.
         */
        BigDecimal takeProfit,

        /**
         * Текущее состояние жизненного цикла позиции.
         */
        PositionStatus status,

        /**
         * Идентификатор запроса на закрытие позиции (если есть).
         */
        UUID closeRequestId,

        /**
         * Время последнего обновления состояния.
         */
        Instant updatedAt
) {

    /**
     * Создаёт пустое состояние позиции (NEW).
     *
     * @param symbol      торговый символ
     * @param strategyId  идентификатор стратегии
     * @return начальное состояние позиции
     */
    public static PositionState empty(String symbol, String strategyId) {
        return new PositionState(
                symbol,
                strategyId,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                BigDecimal.ZERO,
                null,
                null,
                PositionStatus.NEW,
                null,
                Instant.now()
        );
    }
}