package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;
import java.math.BigDecimal;

public class DailyLossRule implements RiskRule {
    private final BigDecimal maxDailyLoss;

    public DailyLossRule(BigDecimal maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss.abs().negate();
    }

    @Override
    public RiskDecision evaluate(OrderRequest request, RiskState state) {
        if (state.getDailyPnl().compareTo(maxDailyLoss) <= 0) {
            return RiskDecision.reject("Daily loss limit reached: " + state.getDailyPnl());
        }
        return RiskDecision.approve(request.getQuantity());
    }}
