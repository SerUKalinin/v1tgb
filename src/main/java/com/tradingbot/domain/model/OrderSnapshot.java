package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Value
public class OrderSnapshot {
    UUID id;
    String clientOrderId;
    String exchangeOrderId;
    String symbol;
    OrderSide side;
    OrderType type;
    BigDecimal quantity;
    BigDecimal price;
    BigDecimal stopLoss;
    BigDecimal takeProfit;
    BigDecimal executedQuantity;
    BigDecimal averagePrice;
    String strategyId;
    OrderStatus status;
}
