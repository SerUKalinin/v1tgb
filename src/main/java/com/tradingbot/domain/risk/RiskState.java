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
    BigDecimal reserved; // Добавлено поле для резерва
    BigDecimal totalEquity;
    BigDecimal dailyPnl;
    BigDecimal maxEquity;
    BigDecimal maxDrawdown;
    Instant lastUpdateTimestamp;
    Map<String, BigDecimal> symbolExposures;
    java.util.Set<String> processedEventIds;
    boolean halted;
    long version;

    /**
     * Проверка финансовых инвариантов.
     * Вызывается после каждого изменения состояния.
     */
    public void validateInvariants() {
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Financial Invariant Violation: balance < 0");
        }
        if (reserved.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Financial Invariant Violation: reserved < 0");
        }
        // available (balance) + reserved <= totalEquity
        if (balance.add(reserved).compareTo(totalEquity.add(new BigDecimal("0.00000001"))) > 0) {
            throw new IllegalStateException("Financial Invariant Violation: balance + reserved > totalEquity");
        }
    }
    
    public static RiskState empty() {
        return RiskState.builder()
                .balance(BigDecimal.ZERO)
                .reserved(BigDecimal.ZERO)
                .totalEquity(BigDecimal.ZERO)
                .dailyPnl(BigDecimal.ZERO)
                .maxEquity(BigDecimal.ZERO)
                .maxDrawdown(BigDecimal.ZERO)
                .lastUpdateTimestamp(Instant.EPOCH)
                .symbolExposures(Map.of())
                .processedEventIds(java.util.Set.of())
                .halted(false)
                .version(0L)
                .build();
    }
}
