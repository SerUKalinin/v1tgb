package com.tradingbot.domain.model;

import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * Окно свечей — контейнер для последовательности свечей.
 */
@Value
public class CandleWindow {

    /**
     * Торговый символ.
     */
    String symbol;

    /**
     * Список свечей в хронологическом порядке.
     */
    List<Candle> candles;

    /**
     * Возвращает список цен закрытия всех свечей.
     *
     * @return список цен закрытия
     */
    public List<BigDecimal> getClosePrices() {
        return candles.stream()
                .map(Candle::getClose)
                .toList();
    }

    /**
     * Возвращает последнюю свечу в окне.
     *
     * @return последняя свеча
     * @throws IllegalStateException если окно пустое
     */
    public Candle getLast() {
        if (candles.isEmpty()) {
            throw new IllegalStateException("Candle window is empty for symbol: " + symbol);
        }
        return candles.get(candles.size() - 1);
    }

    /**
     * Проверяет, содержит ли окно достаточное количество свечей.
     *
     * @param minRequiredSize минимальное требуемое количество
     * @return true, если размер окна не меньше указанного
     */
    public boolean isReady(int minRequiredSize) {
        return candles.size() >= minRequiredSize;
    }

    /**
     * Возвращает количество свечей в окне.
     *
     * @return размер окна
     */
    public int size() {
        return candles.size();
    }

    /**
     * Возвращает торговый символ.
     *
     * @return символ
     */
    public String symbol() {
        return symbol;
    }

    /**
     * Возвращает список свечей.
     *
     * @return список свечей
     */
    public List<Candle> candles() {
        return candles;
    }

    /**
     * Проверяет, не пустое ли окно.
     *
     * @return true, если окно содержит хотя бы одну свечу
     */
    public boolean isReady() {
        return !candles.isEmpty();
    }
}