package com.tradingbot.domain.risk;

import lombok.Value;

import java.math.BigDecimal;

/**
 * Решение риск-менеджера.
 */
@Value
public class RiskDecision {

    /**
     * Флаг одобрения сигнала.
     */
    boolean approved;

    /**
     * Допустимый объём для торговли.
     */
    BigDecimal amount;

    /**
     * Причина отклонения (при approved = false).
     */
    String reason;

    /**
     * Создаёт решение об одобрении сигнала.
     *
     * @param amount допустимый объём
     * @return одобренное решение
     */
    public static RiskDecision approved(BigDecimal amount) {
        return new RiskDecision(true, amount, null);
    }

    /**
     * Создаёт решение об отклонении сигнала.
     *
     * @param reason причина отклонения
     * @return отклонённое решение
     */
    public static RiskDecision rejected(String reason) {
        return new RiskDecision(false, BigDecimal.ZERO, reason);
    }
}