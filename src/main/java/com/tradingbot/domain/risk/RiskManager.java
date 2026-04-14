package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.RiskDecision;
import com.tradingbot.domain.model.Signal;

public interface RiskManager {
    RiskDecision evaluate(Signal signal);
}