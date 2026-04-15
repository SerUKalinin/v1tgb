package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.Signal;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;

/**
 * Интерфейс риск-менеджера.
 */
public interface RiskManager {

    /**
     * Оценивает сигнал до создания ордера.
     */
    RiskDecision evaluate(Signal signal);

    /**
     * Проверяет уже созданный ордер.
     */
    RiskDecision check(OrderEntity order);
}