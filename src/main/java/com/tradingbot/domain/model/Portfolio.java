package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@AllArgsConstructor
public class Portfolio {
    private BigDecimal balance;
    private Map<String, Position> positions;
}