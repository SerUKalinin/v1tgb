package com.tradingbot.domain.risk;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * Решение риск-менеджера.
 */
@Value
@Builder(toBuilder = true)
public class RiskDecision {
    DecisionType type;
    BigDecimal amount;
    Reason reason;
    String message;
    List<String> trace;

    public enum DecisionType {
        APPROVE,
        REJECT,
        REDUCE_SIZE
    }

    public enum Reason {
        APPROVED,
        HALTED,
        DAILY_LIMIT_EXCEEDED,
        EXPOSURE_LIMIT_EXCEEDED,
        INSUFFICIENT_CAPITAL,
        DRAWDOWN_LIMIT_EXCEEDED,
        SYSTEM_ERROR
    }

    public static RiskDecision approve(BigDecimal amount) {
        return RiskDecision.builder()
                .type(DecisionType.APPROVE)
                .amount(amount)
                .reason(Reason.APPROVED)
                .message("Risk check passed")
                .trace(java.util.Collections.singletonList("Legacy approve call"))
                .build();
    }

    public static RiskDecision reject(String message) {
        return RiskDecision.builder()
                .type(DecisionType.REJECT)
                .amount(BigDecimal.ZERO)
                .reason(Reason.SYSTEM_ERROR)
                .message(message)
                .trace(java.util.Collections.singletonList("Legacy reject call: " + message))
                .build();
    }

    public static RiskDecision approve(BigDecimal amount, List<String> trace) {
        return RiskDecision.builder()
                .type(DecisionType.APPROVE)
                .amount(amount)
                .reason(Reason.APPROVED)
                .message("Risk check passed")
                .trace(trace)
                .build();
    }

    public static RiskDecision reject(Reason reason, String message, List<String> trace) {
        return RiskDecision.builder()
                .type(DecisionType.REJECT)
                .amount(BigDecimal.ZERO)
                .reason(reason)
                .message(message)
                .trace(trace)
                .build();
    }

    public boolean isApproved() {
        return type == DecisionType.APPROVE || type == DecisionType.REDUCE_SIZE;
    }
}