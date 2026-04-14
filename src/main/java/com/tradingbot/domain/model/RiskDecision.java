package com.tradingbot.domain.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.math.BigDecimal;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RiskDecision {
    boolean approved;
    String reason;
    BigDecimal amount;
    boolean cooldown;

    public static RiskDecision approved(BigDecimal amount) {
        return new RiskDecision(true, null, amount, false);
    }

    public static RiskDecision rejected(String reason) {
        return new RiskDecision(false, reason, BigDecimal.ZERO, false);
    }

    public static RiskDecision cooldown(String reason) {
        return new RiskDecision(false, reason, BigDecimal.ZERO, true);
    }
}
