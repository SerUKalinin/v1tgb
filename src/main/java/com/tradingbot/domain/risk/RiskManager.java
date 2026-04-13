package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.TradingSignal;
import com.tradingbot.domain.model.Portfolio;

public interface RiskManager {
    RiskDecision evaluate(TradingSignal signal, Portfolio portfolio);
}