package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class DrawdownRule implements RiskRule {
    private final BigDecimal maxDrawdownPercent;
    private BigDecimal peakEquity = BigDecimal.ZERO;

    public DrawdownRule(BigDecimal maxDrawdownPercent) {
        this.maxDrawdownPercent = maxDrawdownPercent;
    }

    @Override
    public RiskDecision evaluate(OrderRequest request, RiskState state) {
        BigDecimal currentEquity = state.getTotalEquity();
        
        if (currentEquity.compareTo(peakEquity) > 0) {
            peakEquity = currentEquity;
        }

        if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drawdown = peakEquity.subtract(currentEquity)
                    .divide(peakEquity, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (drawdown.compareTo(maxDrawdownPercent) >= 0) {
                return RiskDecision.reject("Max drawdown reached: " + drawdown + "%");
            }
        }
        
        return RiskDecision.approve(request.getQuantity());
    }}
