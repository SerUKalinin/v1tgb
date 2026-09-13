package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;

/**
 * Контракт риск-правила.
 * <p>
 * Каждая реализация инкапсулирует отдельную бизнес-проверку,
 * влияющую на итоговое решение риск-менеджмента.
 * Правила выполняются в рамках единого RiskState.
 */
public interface RiskRule {

    /**
     * Оценивает торговый запрос с точки зрения конкретного риск-ограничения.
     *
     * @param request торговый запрос (параметры ордера)
     * @param state текущее состояние риск-системы
     * @return частичное или финальное риск-решение
     */
    RiskDecision evaluate(OrderRequest request, RiskState state);
}