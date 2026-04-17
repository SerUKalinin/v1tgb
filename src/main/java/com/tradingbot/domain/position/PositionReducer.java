package com.tradingbot.domain.position;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Component
public class PositionReducer {

    public PositionState reduce(PositionState state, TradeCreatedEvent event) {
        BigDecimal tradeQty = event.getSide() == OrderSide.BUY ? event.getQuantity() : event.getQuantity().negate();
        BigDecimal newNetQuantity = state.netQuantity().add(tradeQty);
        
        BigDecimal tradePnl = calculateTradePnl(state, event);
        BigDecimal newAveragePrice = calculateNewAvgPrice(state, event, newNetQuantity);

        return new PositionState(
                state.symbol(),
                state.strategyId(),
                newNetQuantity,
                newAveragePrice,
                event.getTradeId(),
                state.realizedPnl().add(tradePnl),
                Instant.now()
        );
    }

    private BigDecimal calculateNewAvgPrice(PositionState state, TradeCreatedEvent event, BigDecimal newQty) {
        if (newQty.signum() == 0) return BigDecimal.ZERO;
        
        int currentSide = state.netQuantity().signum();
        int tradeSide = event.getSide() == OrderSide.BUY ? 1 : -1;

        // Если позиция открывается или увеличивается в ту же сторону
        if (currentSide == 0 || currentSide == tradeSide) {
            BigDecimal currentCost = state.netQuantity().abs().multiply(state.averagePrice());
            BigDecimal tradeCost = event.getQuantity().multiply(event.getPrice());
            return currentCost.add(tradeCost).divide(newQty.abs(), 8, RoundingMode.HALF_UP);
        }
        
        // Если позиция уменьшается, средняя цена входа не меняется
        return state.averagePrice();
    }

    private BigDecimal calculateTradePnl(PositionState state, TradeCreatedEvent event) {
        BigDecimal currentQty = state.netQuantity();
        if (currentQty.signum() == 0) return BigDecimal.ZERO;

        int tradeSideSign = event.getSide() == OrderSide.BUY ? 1 : -1;
        if (currentQty.signum() == tradeSideSign) return BigDecimal.ZERO;

        BigDecimal closedQty = currentQty.abs().min(event.getQuantity());
        BigDecimal priceDiff = event.getSide() == OrderSide.BUY ? 
                state.averagePrice().subtract(event.getPrice()) : 
                event.getPrice().subtract(state.averagePrice());
        
        return priceDiff.multiply(closedQty).setScale(8, RoundingMode.HALF_UP);
    }
}
