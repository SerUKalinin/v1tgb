package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Доменная модель торговой позиции (Position).
 * <p>
 * Представляет агрегированное состояние по инструменту и стратегии,
 * вычисляемое на основе истории сделок (projection).
 * Используется для анализа текущего exposure и управления риском.
 */
@Value
@Builder(toBuilder = true)
public class Position {

    String symbol;

    String strategyId;

    BigDecimal netQuantity;

    BigDecimal avgEntryPrice;

    Instant updatedAt;

    /**
     * Проверяет, является ли позиция открытой.
     * <p>
     * Позиция считается открытой, если netQuantity != 0.
     *
     * @return true если позиция открыта, иначе false
     */
    public boolean isOpen() {
        return netQuantity != null && netQuantity.compareTo(BigDecimal.ZERO) != 0;
    }
}