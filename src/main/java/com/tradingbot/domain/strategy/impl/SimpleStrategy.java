package com.tradingbot.domain.strategy.impl;

import com.tradingbot.domain.model.TradingSignal;
import com.tradingbot.domain.strategy.TradingStrategy;
import com.tradingbot.common.enums.SignalType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component("simpleStrategy")
public class SimpleStrategy implements TradingStrategy {

    @Override
    public TradingSignal analyze(String symbol, BigDecimal price) {

        if (price.compareTo(BigDecimal.valueOf(65000)) < 0) {
            return new TradingSignal(symbol, SignalType.BUY, price, BigDecimal.valueOf(0.8));
        }

        return new TradingSignal(symbol, SignalType.HOLD, price, BigDecimal.ZERO);
    }
}