package com.tradingbot.domain.risk.impl;

import com.tradingbot.config.risk.RiskProperties;
import com.tradingbot.domain.model.Portfolio;
import com.tradingbot.domain.model.TradingSignal;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.application.service.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class AdvancedRiskManager implements RiskManager {

    private final RiskProperties riskProperties;
    private final PositionService positionService;

    @Override
    public RiskDecision evaluate(TradingSignal signal, Portfolio portfolio) {
        if (positionService.hasOpenPosition(signal.getSymbol())) {
            return new RiskDecision(false, "Position already open");
        }
        if (portfolio.getBalance().compareTo(BigDecimal.ZERO) <= 0) {
            return new RiskDecision(false, "No balance");
        }
        // TODO: добавить drawdown, daily loss и т.д.
        return new RiskDecision(true, "OK");
    }
}