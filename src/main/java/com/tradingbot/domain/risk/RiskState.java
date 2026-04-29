package com.tradingbot.domain.risk;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable state of the risk engine.
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class RiskState {
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal reserved = BigDecimal.ZERO;

    public BigDecimal getAvailableBalance() {
        return balance;
    }

    public BigDecimal getReservedMargin() {
        return reserved;
    }

    public BigDecimal availableBalance() {
        return balance;
    }
    @Builder.Default
    private BigDecimal totalEquity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal dailyPnl = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal maxEquity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal maxDrawdown = BigDecimal.ZERO;
    @Builder.Default
    private Instant lastUpdateTimestamp = Instant.EPOCH;
    @Builder.Default
    private Map<String, BigDecimal> symbolExposures = Map.of();
    @Builder.Default
    private Map<UUID, BigDecimal> activeReservations = Map.of();
    @Builder.Default
    private java.util.Set<String> processedEventIds = java.util.Set.of();    private String lastError;
    private boolean halted;
    private long version;

    /**
     * Проверка финансовых инвариантов.
     * Вызывается после каждого изменения состояния.
     */
    public void validateInvariants() {
        if (safeCompare(balance, BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Financial Invariant Violation: balance < 0");
        }
        if (safeCompare(reserved, BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Financial Invariant Violation: reserved < 0");
        }
        // available (balance) + reserved <= totalEquity
        if (safeCompare(safeAdd(balance, reserved), totalEquity.add(new BigDecimal("0.00000001"))) > 0) {
            throw new IllegalStateException("Financial Invariant Violation: balance + reserved > totalEquity");
        }
    }

    public static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b);
    }

    public static int safeCompare(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).compareTo(b == null ? BigDecimal.ZERO : b);
    }
    
    public static RiskState empty() {
        return RiskState.builder().build();
    }
}
