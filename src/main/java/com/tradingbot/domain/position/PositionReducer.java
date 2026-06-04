package com.tradingbot.domain.position;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Slf4j
public class PositionReducer {

    public PositionState reduce(PositionState state, TradeCreatedEvent event) {
        BigDecimal tradeQty = event.getSide() == OrderSide.BUY ? event.getQuantity() : event.getQuantity().negate();
        BigDecimal newNetQuantity = state.netQuantity().add(tradeQty);

        BigDecimal tradePnl = calculateTradePnl(state, event);
        BigDecimal newAveragePrice = calculateNewAvgPrice(state, event, newNetQuantity);

        // Логика Stage 3.5:
        // 1. TP/SL фиксируются только при открытии новой позиции (статус NEW)
        // 2. После первого входа статус становится OPEN
        // 3. Если позиция закрылась в ноль, статус становится CLOSED
        
        BigDecimal newStopLoss = state.stopLoss();
        BigDecimal newTakeProfit = state.takeProfit();
        PositionStatus newStatus = state.status();

        if (state.status() == PositionStatus.NEW && newNetQuantity.signum() != 0) {
            newStopLoss = event.getStopLoss();
            newTakeProfit = event.getTakeProfit();
            newStatus = PositionStatus.OPEN;
            log.info("Position opened for {}. TP: {}, SL: {}", state.symbol(), newTakeProfit, newStopLoss);
        } else if (newNetQuantity.signum() == 0) {
            newStatus = PositionStatus.CLOSED;
            log.info("Position closed for {}", state.symbol());
        }

        return new PositionState(
                state.symbol(),
                state.strategyId(),
                newNetQuantity,
                newAveragePrice,
                event.getTradeId(),
                state.realizedPnl().add(tradePnl),
                newStopLoss,
                newTakeProfit,
                newStatus,
                state.closeRequestId(),
                Instant.now()
        );
    }

    private BigDecimal calculateNewAvgPrice(PositionState state, TradeCreatedEvent event, BigDecimal newQty) {
        if (newQty.signum() == 0) return BigDecimal.ZERO;

        BigDecimal currentQty = state.netQuantity();
        BigDecimal tradeQty = event.getSide() == OrderSide.BUY ? event.getQuantity() : event.getQuantity().negate();

        // 1. Открытие новой позиции или увеличение существующей в ту же сторону
        if (currentQty.signum() == 0 || currentQty.signum() == tradeQty.signum()) {
            BigDecimal currentCost = currentQty.abs().multiply(state.averagePrice());
            BigDecimal tradeCost = event.getQuantity().multiply(event.getPrice());
            return currentCost.add(tradeCost).divide(newQty.abs(), 8, RoundingMode.HALF_UP);
        }

        // 2. Разворот позиции (Reversal)
        if (currentQty.abs().compareTo(event.getQuantity()) < 0) {
            return event.getPrice(); // Новая цена входа — цена разворотной сделки
        }

        // 3. Частичное закрытие (уменьшение) — средняя цена входа не меняется
        return state.averagePrice();
    }

    private BigDecimal calculateTradePnl(PositionState state, TradeCreatedEvent event) {
        BigDecimal currentQty = state.netQuantity();
        if (currentQty.signum() == 0) return BigDecimal.ZERO;

        int tradeSideSign = event.getSide() == OrderSide.BUY ? 1 : -1;
        // Если сделка в ту же сторону, что и позиция — PnL не фиксируется
        if (currentQty.signum() == tradeSideSign) return BigDecimal.ZERO;

        // Фиксируем PnL только на закрытую часть объема
        BigDecimal closedQty = currentQty.abs().min(event.getQuantity());
        BigDecimal priceDiff = event.getSide() == OrderSide.BUY ?
                state.averagePrice().subtract(event.getPrice()) :
                event.getPrice().subtract(state.averagePrice());

        return priceDiff.multiply(closedQty).setScale(8, RoundingMode.HALF_UP);
    }
}
