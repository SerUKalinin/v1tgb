package com.tradingbot.domain.execution;

import lombok.Value;

import java.math.BigDecimal;

@Value
public class ExecutionResult {
    String orderId;
    String symbol;
    BigDecimal price;
    BigDecimal amount;
    boolean success;
}