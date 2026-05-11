package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.tracing.ExecutionContext;
import lombok.Getter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class TradeCreatedEvent extends DomainEvent {
    private final UUID tradeId;
    private final UUID orderId;
    private final String symbol;
    private final String strategyId;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final OrderSide side;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;

    public TradeCreatedEvent(ExecutionContext context, UUID tradeId, UUID orderId, String symbol, String strategyId, 
                             BigDecimal quantity, BigDecimal price, OrderSide side,
                             BigDecimal stopLoss, BigDecimal takeProfit) {
        super(context, 1);
        this.tradeId = tradeId;
        this.orderId = orderId;
        this.symbol = symbol;
        this.strategyId = strategyId;
        this.quantity = quantity;
        this.price = price;
        this.side = side;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
    }

    @Override
    public String getEventType() {
        return "TRADE_CREATED";
    }
}
