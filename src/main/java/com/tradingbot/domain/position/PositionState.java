package com.tradingbot.domain.position;

import java.math.BigDecimal;
import java.time.Instant;

import java.util.UUID;

public record PositionState(
    String symbol,
    String strategyId,
    BigDecimal netQuantity,
    BigDecimal averagePrice,
    Long lastTradeId,
    BigDecimal realizedPnl,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    PositionStatus status,
    UUID closeRequestId,
    Instant updatedAt
) {
    public static PositionState empty(String symbol, String strategyId) {
        return new PositionState(
            symbol, 
            strategyId, 
            BigDecimal.ZERO, 
            BigDecimal.ZERO, 
            -1L, 
            BigDecimal.ZERO,
            null,
            null,
            PositionStatus.NEW,
            null,
            Instant.now()
        );
    }
}
