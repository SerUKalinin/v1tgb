package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@AllArgsConstructor
public class MarketData {
    private String symbol;
    private BigDecimal price;
    private Instant timestamp;
}