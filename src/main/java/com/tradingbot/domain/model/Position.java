package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Position {
    private String symbol;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
}