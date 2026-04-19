package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Окно свечей — контейнер для последовательности свечей.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CandleWindow {

    /**
     * Торговый символ.
     */
    private String symbol;

    /**
     * Список свечей в хронологическом порядке.
     */
    private List<Candle> candles;

    /**
     * Возвращает список цен закрытия всех свечей.
     */
    public List<BigDecimal> getClosePrices() {
        return candles.stream()
                .map(Candle::getClose)
                .toList();
    }

    /**
     * Возвращает последнюю свечу в окне.
     */
    public Candle getLast() {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalStateException("Candle window is empty for symbol: " + symbol);
        }
        return candles.get(candles.size() - 1);
    }

    /**
     * Проверяет, содержит ли окно достаточное количество свечей.
     */
    public boolean isReady(int minRequiredSize) {
        return candles != null && candles.size() >= minRequiredSize;
    }

    public int size() {
        return candles != null ? candles.size() : 0;
    }

    public boolean isReady() {
        return candles != null && !candles.isEmpty();
    }
}