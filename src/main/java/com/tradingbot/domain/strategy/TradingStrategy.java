package com.tradingbot.domain.strategy;

import com.tradingbot.domain.model.MarketData;
import com.tradingbot.domain.model.TradingSignal;

public interface TradingStrategy {
    TradingSignal generateSignal(MarketData data);
}