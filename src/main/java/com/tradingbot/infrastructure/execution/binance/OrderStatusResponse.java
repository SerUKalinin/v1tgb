package com.tradingbot.infrastructure.execution.binance;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;

@Value
@Builder
public class OrderStatusResponse {
    String status;
    BigDecimal executedQty;
    BigDecimal price;
    String exchangeOrderId;
    String clientOrderId;

    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String UNKNOWN = "UNKNOWN";
}
