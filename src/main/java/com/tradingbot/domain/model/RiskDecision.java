package com.tradingbot.domain.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Решение риск-менеджера.
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RiskDecision {

    /**
     * Флаг одобрения сигнала.
     */
    boolean approved;

    /**
     * Причина решения (для rejected или cooldown).
     */
    String reason;

    /**
     * Допустимый объём для торговли.
     */
    BigDecimal amount;

    /**
     * Флаг состояния охлаждения системы.
     */
    boolean cooldown;

    /**
     * Создаёт решение об одобрении сигнала.
     *
     * @param amount допустимый объём
     * @return одобренное решение
     */
    public static RiskDecision approved(BigDecimal amount) {
        return new RiskDecision(true, null, amount, false);
    }

    /**
     * Создаёт решение об отклонении сигнала.
     *
     * @param reason причина отклонения
     * @return отклонённое решение
     */
    public static RiskDecision rejected(String reason) {
        return new RiskDecision(false, reason, BigDecimal.ZERO, false);
    }

    /**
     * Создаёт решение о приостановке торговли (режим охлаждения).
     *
     * @param reason причина
     * @return решение с cooldown = true
     */
    public static RiskDecision cooldown(String reason) {
        return new RiskDecision(false, reason, BigDecimal.ZERO, true);
    }
}