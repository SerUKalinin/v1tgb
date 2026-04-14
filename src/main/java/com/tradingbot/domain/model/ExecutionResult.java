package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

@Value
public class ExecutionResult {
    String orderId;
    String symbol;
    OrderSide side;
    BigDecimal executedQty;
    BigDecimal executedPrice;
    Instant executedAt;
    boolean success;
    String errorMessage;

    public static ExecutionResult success(String orderId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price) {
        return new ExecutionResult(orderId, symbol, side, qty, price, Instant.now(), true, null);
    }

    public static ExecutionResult failure(String symbol, String errorMessage) {
        return new ExecutionResult(null, symbol, null, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), false, errorMessage);
    }
}
