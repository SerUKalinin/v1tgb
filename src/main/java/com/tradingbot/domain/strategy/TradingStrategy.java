package com.tradingbot.domain.strategy;

import com.tradingbot.domain.model.MarketData;
import com.tradingbot.domain.model.Signal;

public interface TradingStrategy {
    Signal generateSignal(MarketData data);
}