package com.tradingbot.domain.position;

import java.math.BigDecimal;

/**
 * Domain-порт для получения доступного количества актива
 * для торговой позиции.
 *
 * <p>Доменный слой не зависит от конкретного persistence механизма.</p>
 */
public interface PositionAvailabilityPort {

    /**
     * Возвращает текущий доступный объём позиции.
     *
     * @param symbol торговый символ
     * @param strategyId идентификатор стратегии
     * @return доступное количество актива, либо ZERO если позиция отсутствует
     */
    BigDecimal getAvailableQuantity(String symbol, String strategyId);
}