package com.tradingbot.domain.risk.impl;

import com.tradingbot.application.service.PositionService;
import com.tradingbot.domain.model.TradingSignal;
import com.tradingbot.domain.risk.RiskManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class AdvancedRiskManager implements RiskManager {

    private final PositionService positionService;

    @Override
    public boolean approve(TradingSignal signal, BigDecimal price) {

        if (positionService.hasOpenPosition(signal.symbol())) {
            return false;
        }

        return price.compareTo(BigDecimal.ZERO) > 0;
    }
}