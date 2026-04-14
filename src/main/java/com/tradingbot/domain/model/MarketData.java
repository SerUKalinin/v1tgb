package com.tradingbot.domain.model;

import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketData(
        String symbol,
        BigDecimal price,
        Instant timestamp
) {}