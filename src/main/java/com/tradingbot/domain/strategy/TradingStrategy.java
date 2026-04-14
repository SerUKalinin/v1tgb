package com.tradingbot.domain.strategy;

import com.tradingbot.domain.model.TradingSignal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

public interface TradingStrategy {
    TradingSignal analyze(String symbol, BigDecimal price);
}