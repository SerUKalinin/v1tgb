package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

import com.tradingbot.common.enums.OrderType;

@Value
@Builder(toBuilder = true)
public class OrderRequest {
    String orderId;
    String clientOrderId;
    String symbol;
    BigDecimal quantity;
    BigDecimal amount; // Alias for quantity to support legacy code
    OrderSide side;
    OrderType type;
    BigDecimal price;
    String strategyId;

    public BigDecimal getAmount() {
        return amount != null ? amount : quantity;
    }
}