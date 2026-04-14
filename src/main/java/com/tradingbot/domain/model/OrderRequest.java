package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

import java.util.UUID;

@Value
@Builder
public class OrderRequest {
    String symbol;
    OrderSide side;
    BigDecimal amount;
    BigDecimal price;
    String clientOrderId;
}
