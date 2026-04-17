package com.tradingbot.application.event;


import java.math.BigDecimal;
import java.time.Instant;

public record NewClosedCandleEvent(
        String symbol,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        Instant closeTime
) {}