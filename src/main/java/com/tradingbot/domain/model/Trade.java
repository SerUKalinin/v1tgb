package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Чистая доменная модель сделки (Trade).
 * Является неизменяемым фактом исполнения ордера.
 */
@Value
@Builder
public class Trade {
    UUID id;
    String exchangeTradeId;
    UUID orderId;    String clientOrderId;    String symbol;
    String strategyId;
    OrderSide side;
    BigDecimal price;
    BigDecimal quantity;
    BigDecimal feeAmount;
    String feeAsset;
    BigDecimal realizedPnl;
    Instant executedAt;
}
