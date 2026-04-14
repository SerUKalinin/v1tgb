package com.tradingbot.domain.risk;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class RiskDecision {
    boolean approved;
    BigDecimal amount;
    String reason;

    public static RiskDecision approved(BigDecimal amount) {
        return new RiskDecision(true, amount, null);
    }

    public static RiskDecision rejected(String reason) {
        return new RiskDecision(false, BigDecimal.ZERO, reason);
    }
}