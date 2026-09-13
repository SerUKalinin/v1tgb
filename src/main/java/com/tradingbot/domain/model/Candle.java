package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Модель торговой свечи (OHLCV).
 * <p>
 * Представляет агрегированные рыночные данные за фиксированный временной интервал:
 * open, high, low, close и volume. Используется в аналитике, стратегиях и
 * торговых решениях.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Candle {

    /**
     * Торговый символ инструмента.
     */
    String symbol;

    /**
     * Цена открытия периода.
     */
    BigDecimal open;

    /**
     * Максимальная цена за период.
     */
    BigDecimal high;

    /**
     * Минимальная цена за период.
     */
    BigDecimal low;

    /**
     * Цена закрытия периода.
     */
    BigDecimal close;

    /**
     * Объём торгов за период.
     */
    BigDecimal volume;

    /**
     * Время начала формирования свечи.
     */
    Instant openTime;

    /**
     * Время завершения формирования свечи.
     */
    Instant closeTime;

    /**
     * Признак завершённости свечи.
     * true — свеча закрыта и не изменяется,
     * false — свеча ещё формируется.
     */
    boolean isClosed;

    /**
     * Возвращает время открытия свечи.
     *
     * @return время открытия свечи
     */
    public Instant getOpenTime() {
        return openTime;
    }

    /**
     * Создаёт экземпляр свечи.
     *
     * @param symbol    торговый символ
     * @param open      цена открытия
     * @param high      максимальная цена
     * @param low       минимальная цена
     * @param close     цена закрытия
     * @param volume    объём торгов
     * @param openTime  время открытия
     * @param closeTime время закрытия
     * @param isClosed  признак завершённости свечи
     * @return новая свеча
     */
    public static Candle of(String symbol,
                            BigDecimal open,
                            BigDecimal high,
                            BigDecimal low,
                            BigDecimal close,
                            BigDecimal volume,
                            Instant openTime,
                            Instant closeTime,
                            boolean isClosed) {
        return new Candle(symbol, open, high, low, close, volume, openTime, closeTime, isClosed);
    }
}