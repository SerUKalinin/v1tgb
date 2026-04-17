package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
public class TradeCreatedEvent extends DomainEvent {
    private final Long tradeId;
    private final String orderId;
    private final String symbol;
    private final String strategyId;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final OrderSide side;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;

    public TradeCreatedEvent(Long tradeId, String orderId, String symbol, String strategyId, 
                             BigDecimal quantity, BigDecimal price, OrderSide side,
                             BigDecimal stopLoss, BigDecimal takeProfit) {
        super();
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
}
