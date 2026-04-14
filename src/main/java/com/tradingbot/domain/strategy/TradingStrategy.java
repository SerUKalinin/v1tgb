package com.tradingbot.domain.strategy;

import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;

public interface TradingStrategy {
    Signal analyze(CandleWindow window);
}