package com.tradingbot.domain.event;

import lombok.Getter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class OrderFilledEvent extends DomainEvent {
    private final UUID orderId;
    private final String externalExecutionId;
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal price;

    public OrderFilledEvent(UUID orderId, String externalExecutionId, String symbol, BigDecimal quantity, BigDecimal price) {
        super();
        this.orderId = orderId;
        this.externalExecutionId = externalExecutionId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }
}
