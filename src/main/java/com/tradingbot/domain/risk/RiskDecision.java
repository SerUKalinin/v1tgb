package com.tradingbot.domain.risk;

import lombok.Value;

import java.math.BigDecimal;

/**
 * Решение риск-менеджера.
 */
@Value
public class RiskDecision {
    DecisionType type;
    BigDecimal amount;
    String reason;

    public enum DecisionType {
        APPROVE,
        REJECT,
        REDUCE_SIZE
    }

    public static RiskDecision approve() {
        return new RiskDecision(DecisionType.APPROVE, null, "Risk check passed");
    }

    public static RiskDecision approve(BigDecimal amount) {
        return new RiskDecision(DecisionType.APPROVE, amount, "Risk check passed");
    }
    public static RiskDecision reject(String reason) {
        return new RiskDecision(DecisionType.REJECT, BigDecimal.ZERO, reason);
    }

    public static RiskDecision reduce(BigDecimal newAmount, String reason) {
        return new RiskDecision(DecisionType.REDUCE_SIZE, newAmount, reason);
    }

    public boolean isApproved() {
        return type == DecisionType.APPROVE || type == DecisionType.REDUCE_SIZE;
    }
}