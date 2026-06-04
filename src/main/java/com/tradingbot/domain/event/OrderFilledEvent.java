package com.tradingbot.domain.event;

import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.BusinessContext;
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

    public OrderFilledEvent(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business, 
                            UUID orderId, String externalExecutionId, String symbol, BigDecimal quantity, BigDecimal price) {
        super(identity, attempt, business, 1);
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
