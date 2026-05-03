package com.tradingbot.domain.exchange;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class FeasibilityResult {
    boolean feasible;
    String reason;

    public static FeasibilityResult success() {
        return new FeasibilityResult(true, null);
    }

    public static FeasibilityResult rejected(String reason) {
        return new FeasibilityResult(false, reason);
    }
}
