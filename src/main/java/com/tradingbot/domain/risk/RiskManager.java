package com.tradingbot.domain.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;

import java.util.Optional;

/**
 * Интерфейс риск-менеджера.
 */
public interface RiskManager {

    /**
     * Принимает сигнал и возвращает одобренный ордер с рассчитанным объемом.
     * Если риск-движок отклоняет сигнал, возвращает Optional.empty().
     */
    Optional<ApprovedOrder> approveSignal(SignalEvent signal);

    /**
     * Проверяет уже созданный ордер.
     */
    RiskDecision check(OrderEntity order);
}