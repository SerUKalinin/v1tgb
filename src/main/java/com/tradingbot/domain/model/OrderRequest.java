package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Запрос на исполнение торгового ордера.
 */
@Value
@Builder
public class OrderRequest {
    String orderId; // Добавлено поле
    String symbol;
    OrderSide side;
    BigDecimal amount;
    BigDecimal price;
    String strategyId;
    String clientOrderId;
}