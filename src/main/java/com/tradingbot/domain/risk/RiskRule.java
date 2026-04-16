package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;

/**
 * Interface for risk validation rules.
 */
public interface RiskRule {
    RiskDecision evaluate(OrderRequest request, RiskState state);
}
