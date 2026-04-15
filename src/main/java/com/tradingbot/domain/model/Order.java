package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Чистая доменная модель ордера (Order).
 * Представляет намерение совершить сделку.
 */
@Value
@Builder(toBuilder = true)
public class Order {
    String id;
    String clientOrderId;
    String exchangeOrderId;
    String symbol;
    String strategyId;
    OrderSide side;
    BigDecimal quantity;
    BigDecimal price;
    String status;
    Instant createdAt;
    Instant updatedAt;
}
