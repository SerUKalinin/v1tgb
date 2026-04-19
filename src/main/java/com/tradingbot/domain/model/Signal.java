package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pure Domain Signal model.
 */
@Value
@Builder
public class Signal {
    String clientOrderId;
    String symbol;
    OrderSide side;
    BigDecimal price;
    BigDecimal quantity; // Optional
    String strategyId;
    Instant generatedAt;
}