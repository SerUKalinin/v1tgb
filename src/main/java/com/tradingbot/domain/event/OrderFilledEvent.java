package com.tradingbot.domain.event;

import lombok.Getter;
import java.math.BigDecimal;

@Getter
public class OrderFilledEvent extends DomainEvent {
    private final String orderId;
    private final String externalExecutionId;
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal price;

    public OrderFilledEvent(String orderId, String externalExecutionId, String symbol, BigDecimal quantity, BigDecimal price) {
        super();
        this.orderId = orderId;
        this.externalExecutionId = externalExecutionId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }
}
