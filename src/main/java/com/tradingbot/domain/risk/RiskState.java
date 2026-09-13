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
 * Иммутабельное состояние риск-движка.
 *
 * <p>Содержит:
 * <ul>
 *   <li>балансовые показатели</li>
 *   <li>зарезервированную маржу</li>
 *   <li>экспозиции по инструментам</li>
 *   <li>состояние обработки событий</li>
 *   <li>флаг остановки торговли</li>
 * </ul>
 *
 * <p>Используется как единый источник истины для risk-engine.
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class RiskState {

    /**
     * Доступный баланс (cash-like часть капитала).
     */
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

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
    private java.util.Set<String> processedEventIds = java.util.Set.of();

    private String lastError;

    private boolean halted;

    private long version;

    /**
     * Возвращает доступный баланс (alias).
     */
    public BigDecimal getAvailableBalance() {
        return balance;
    }

    /**
     * Возвращает сумму всех активных резервов.
     */
    public BigDecimal getReservedMargin() {
        return getReserved();
    }

    /**
     * Вычисляет суммарный зарезервированный капитал.
     */
    public BigDecimal getReserved() {
        if (activeReservations == null || activeReservations.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return activeReservations.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Алиас доступного баланса.
     */
    public BigDecimal availableBalance() {
        return balance;
    }

    /**
     * Проверка внутренних финансовых инвариантов состояния.
     *
     * <p>Гарантирует:
     * <ul>
     *   <li>balance >= 0</li>
     *   <li>reserved >= 0</li>
     *   <li>balance + reserved <= totalEquity</li>
     * </ul>
     */
    public void validateInvariants() {
        if (safeCompare(balance, BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Financial Invariant Violation: balance < 0");
        }

        BigDecimal currentReserved = getReserved();

        if (safeCompare(currentReserved, BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Financial Invariant Violation: reserved < 0");
        }

        if (safeCompare(safeAdd(balance, currentReserved),
                totalEquity.add(new BigDecimal("0.00000001"))) > 0) {
            throw new IllegalStateException(
                    "Financial Invariant Violation: balance + reserved > totalEquity"
            );
        }
    }

    /**
     * Безопасное сложение BigDecimal с null-check.
     */
    public static BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a)
                .add(b == null ? BigDecimal.ZERO : b);
    }

    /**
     * Безопасное сравнение BigDecimal с null-check.
     */
    public static int safeCompare(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a)
                .compareTo(b == null ? BigDecimal.ZERO : b);
    }

    /**
     * Создаёт пустое состояние риск-движка.
     */
    public static RiskState empty() {
        return RiskState.builder().build();
    }
}