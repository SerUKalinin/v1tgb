package com.tradingbot.domain.event;

import com.tradingbot.tracing.ExecutionContext;
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

    public OrderFilledEvent(ExecutionContext context, UUID orderId, String externalExecutionId, String symbol, BigDecimal quantity, BigDecimal price) {
        super(context, 1);
        this.orderId = orderId;
        this.externalExecutionId = externalExecutionId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }

    @Override
    public String getEventType() {
        return "ORDER_FILLED";
    }
}
