package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class TradeExecutedEvent {
    String orderId;
    String exchangeTradeId;
    String clientOrderId;
    String symbol;
    String strategyId;
    OrderSide side;
    BigDecimal quantity;
    BigDecimal price;
    BigDecimal feeAmount;
    String feeAsset;
    Instant executedAt;
}
