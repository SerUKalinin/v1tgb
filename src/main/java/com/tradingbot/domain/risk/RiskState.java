package com.tradingbot.domain.risk;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable state of the risk engine.
 */
@Value
@Builder(toBuilder = true)
public class RiskState {
    BigDecimal balance;
    BigDecimal totalEquity;
    BigDecimal dailyPnl;
    BigDecimal maxEquity;
    BigDecimal maxDrawdown;
    Instant lastUpdateTimestamp;
    Map<String, BigDecimal> symbolExposures;
    java.util.Set<String> processedEventIds;
    
    public static RiskState empty() {
        return RiskState.builder()
                .balance(BigDecimal.ZERO)
                .totalEquity(BigDecimal.ZERO)
                .dailyPnl(BigDecimal.ZERO)
                .maxEquity(BigDecimal.ZERO)
                .maxDrawdown(BigDecimal.ZERO)
                .lastUpdateTimestamp(Instant.EPOCH)
                .symbolExposures(Map.of())
                .processedEventIds(java.util.Set.of())
                .build();
    }
}
