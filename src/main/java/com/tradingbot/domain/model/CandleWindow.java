package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Окно свечей (Candle Window).
 * <p>
 * Представляет упорядоченную по времени последовательность свечей одного символа.
 * Используется в аналитике, индикаторах и торговых стратегиях для расчёта
 * агрегированных рыночных метрик.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CandleWindow {

    /**
     * Торговый символ инструмента.
     */
    private String symbol;

    /**
     * Список свечей в хронологическом порядке (от старых к новым).
     */
    private List<Candle> candles;

    /**
     * Возвращает список цен закрытия всех свечей в окне.
     *
     * @return список цен закрытия
     */
    public List<BigDecimal> getClosePrices() {
        return candles.stream()
                .map(Candle::getClose)
                .toList();
    }

    /**
     * Возвращает последнюю (самую актуальную) свечу в окне.
     *
     * @return последняя свеча
     * @throws IllegalStateException если окно пустое или не инициализировано
     */
    public Candle getLast() {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalStateException("Candle window is empty for symbol: " + symbol);
        }
        return candles.get(candles.size() - 1);
    }

    /**
     * Проверяет, содержит ли окно достаточное количество свечей для анализа.
     *
     * @param minRequiredSize минимально необходимый размер окна
     * @return true, если размер окна >= minRequiredSize
     */
    public boolean isReady(int minRequiredSize) {
        return candles != null && candles.size() >= minRequiredSize;
    }

    /**
     * Возвращает количество свечей в окне.
     *
     * @return размер окна (0, если список не инициализирован)
     */
    public int size() {
        return candles != null ? candles.size() : 0;
    }

    /**
     * Возвращает торговый символ.
     *
     * @return символ инструмента
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * Возвращает список свечей.
     *
     * @return список свечей (может быть null)
     */
    public List<Candle> getCandles() {
        return candles;
    }

    /**
     * Проверяет, содержит ли окно хотя бы одну свечу.
     *
     * @return true если есть хотя бы одна свеча
     */
    public boolean isReady() {
        return candles != null && !candles.isEmpty();
    }
}