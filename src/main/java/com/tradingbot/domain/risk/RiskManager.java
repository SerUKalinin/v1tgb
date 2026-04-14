package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.RiskDecision;
import com.tradingbot.domain.model.Signal;

/**
 * Менеджер рисков для оценки торговых сигналов.
 */
public interface RiskManager {

    /**
     * Оценивает торговый сигнал и возвращает решение.
     *
     * @param signal торговый сигнал
     * @return решение риск-менеджера
     */
    RiskDecision evaluate(Signal signal);
}