package com.tradingbot.domain.model;

import com.tradingbot.common.enums.SignalType;

import java.math.BigDecimal;

public record TradingSignal(
        String symbol,
        SignalType type,
        BigDecimal price,
        BigDecimal confidence
) {}