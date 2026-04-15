package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class OrderEvent {
    String orderId;
    String clientOrderId;
    String exchangeOrderId;
    String symbol;
    String strategyId;
    OrderSide side;
    OrderStatus status;
    BigDecimal quantity;
    BigDecimal price;
    Instant timestamp;
    String message;
}
