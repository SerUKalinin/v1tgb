package com.tradingbot.domain.strategy;

import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;

/**
 * Контракт торговой стратегии.
 *
 * <p>Стратегия является чистой функцией анализа рыночных данных:
 * на вход получает историческое окно свечей (CandleWindow)
 * и формирует торговый сигнал.</p>
 *
 * <p>Реализации не должны содержать:
 * <ul>
 *   <li>состояние между вызовами</li>
 *   <li>доступ к инфраструктуре (БД, сеть, кеш)</li>
 *   <li>побочных эффектов</li>
 * </ul>
 * </p>
 */
public interface TradingStrategy {

    /**
     * Выполняет анализ рыночных данных и формирует торговый сигнал.
     *
     * @param window окно свечей с историческими данными
     * @return сигнал BUY / SELL / HOLD (в зависимости от стратегии)
     */
    Signal analyze(CandleWindow window);
}