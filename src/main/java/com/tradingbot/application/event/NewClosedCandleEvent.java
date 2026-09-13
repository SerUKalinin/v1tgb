package com.tradingbot.application.event;


import java.math.BigDecimal;
import java.time.Instant;

/**
 * Событие закрытой свечи (Candle Close Event).
 *
 * Представляет собой доменное событие, которое возникает
 * в момент закрытия рыночной свечи и передаётся в стратегический слой
 * для генерации торговых сигналов.
 *
 * Является immutable DTO (record) и используется как входной триггер
 * для StrategyEngine.
 *
 * Инвариант: все поля соответствуют финальному состоянию свечи
 * на момент её закрытия.
 */
public record NewClosedCandleEvent(

        /**
         * Торговый символ (например BTCUSDT).
         */
        String symbol,

        /**
         * Цена открытия свечи.
         */
        BigDecimal open,

        /**
         * Максимальная цена за период свечи.
         */
        BigDecimal high,

        /**
         * Минимальная цена за период свечи.
         */
        BigDecimal low,

        /**
         * Цена закрытия свечи.
         */
        BigDecimal close,

        /**
         * Объём торгов за свечу.
         */
        BigDecimal volume,

        /**
         * Время закрытия свечи.
         */
        Instant closeTime
) {}