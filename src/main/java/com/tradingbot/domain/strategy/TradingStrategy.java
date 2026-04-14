package com.tradingbot.domain.strategy;

import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;

/**
 * Торговая стратегия для анализа рыночных данных.
 */
public interface TradingStrategy {

    /**
     * Анализирует окно свечей и возвращает торговый сигнал.
     *
     * @param window окно свечей
     * @return торговый сигнал (BUY, SELL или HOLD)
     */
    Signal analyze(CandleWindow window);
}