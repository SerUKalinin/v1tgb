package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Модель свечи (OHLCV).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Candle {

    /**
     * Торговый символ.
     */
    String symbol;

    /**
     * Цена открытия.
     */
    BigDecimal open;

    /**
     * Максимальная цена.
     */
    BigDecimal high;

    /**
     * Минимальная цена.
     */
    BigDecimal low;

    /**
     * Цена закрытия.
     */
    BigDecimal close;

    /**
     * Объём торгов.
     */
    BigDecimal volume;

    /**
     * Время открытия свечи.
     */
    Instant openTime;

    /**
     * Время закрытия свечи.
     */
    Instant closeTime;

    /**
     * Флаг закрытости свечи.
     */
    boolean isClosed;

    /**
     * Создаёт новую свечу.
     */
    public static Candle of(String symbol, BigDecimal open, BigDecimal high, BigDecimal low,
                            BigDecimal close, BigDecimal volume, Instant openTime,
                            Instant closeTime, boolean isClosed) {
        return new Candle(symbol, open, high, low, close, volume, openTime, closeTime, isClosed);
    }
}