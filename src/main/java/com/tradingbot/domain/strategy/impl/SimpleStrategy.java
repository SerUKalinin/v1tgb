package com.tradingbot.domain.strategy.impl;

import com.tradingbot.domain.model.MarketData;
import com.tradingbot.domain.model.TradingSignal;
import com.tradingbot.domain.strategy.TradingStrategy;
import com.tradingbot.common.enums.SignalType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class SimpleStrategy implements TradingStrategy {
    @Override
    public TradingSignal generateSignal(MarketData data) {
        if (data.getPrice().compareTo(BigDecimal.valueOf(65000)) < 0) {
            return new TradingSignal(SignalType.BUY, data.getSymbol(), data.getPrice(), data.getPrice().multiply(BigDecimal.valueOf(0.98)));
        }
        return new TradingSignal(SignalType.HOLD, data.getSymbol(), data.getPrice(), null);
    }
}