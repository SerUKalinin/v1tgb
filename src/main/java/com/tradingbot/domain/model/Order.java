package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class Order {
    private String symbol;
    private OrderSide side;
    private BigDecimal quantity;
    private BigDecimal price;
}