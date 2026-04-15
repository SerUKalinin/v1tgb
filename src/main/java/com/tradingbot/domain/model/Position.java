package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Чистая доменная модель позиции (Position).
 * Является производным состоянием (Projection) на основе списка сделок.
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
     * Проверка, открыта ли позиция.
     */
    public boolean isOpen() {
        return netQuantity != null && netQuantity.compareTo(BigDecimal.ZERO) != 0;
    }
}
