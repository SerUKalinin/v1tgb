package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Value;

import java.math.BigDecimal;

@Value
public class OrderRequest {
    String symbol;
    OrderSide side;
    BigDecimal quantity;
    BigDecimal price;
}