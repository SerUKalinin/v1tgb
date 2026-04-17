package com.tradingbot.domain.position;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionState(
    String symbol,
    String strategyId,
    BigDecimal netQuantity,
    BigDecimal averagePrice,
    Long lastTradeId,
    BigDecimal realizedPnl,
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
            Instant.now()
        );
    }
}
