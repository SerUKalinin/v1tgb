package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.TradingSignal;

import java.math.BigDecimal;

public interface RiskManager {
    boolean approve(TradingSignal signal, BigDecimal price);
}