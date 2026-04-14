package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Модель свечи (OHLCV).
 */
@Value
@Builder
@AllArgsConstructor
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
     * Возвращает время открытия свечи.
     *
     * @return время открытия
     */
    public Instant openTime() {
        return openTime;
    }

    /**
     * Создаёт новую свечу.
     *
     * @param symbol    торговый символ
     * @param open      цена открытия
     * @param high      максимальная цена
     * @param low       минимальная цена
     * @param close     цена закрытия
     * @param volume    объём
     * @param openTime  время открытия
     * @param closeTime время закрытия
     * @param isClosed  флаг закрытости
     * @return новая свеча
     */
    public static Candle of(String symbol, BigDecimal open, BigDecimal high, BigDecimal low,
                            BigDecimal close, BigDecimal volume, Instant openTime,
                            Instant closeTime, boolean isClosed) {
        return new Candle(symbol, open, high, low, close, volume, openTime, closeTime, isClosed);
    }
}