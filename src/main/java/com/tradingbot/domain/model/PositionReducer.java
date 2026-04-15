package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Pure function to calculate next position state from current state and trade event.
 * Ensures deterministic state transitions for replay and live trading.
 */
@Slf4j
public class PositionReducer {

    public static Position reduce(Position current, TradeCreatedEvent event) {
        BigDecimal currentQty = current.getNetQuantity() != null ? current.getNetQuantity() : BigDecimal.ZERO;
        BigDecimal currentEntryPrice = current.getAvgEntryPrice() != null ? current.getAvgEntryPrice() : BigDecimal.ZERO;

        BigDecimal executedQty = event.getSide() == OrderSide.BUY 
                ? event.getQuantity() 
                : event.getQuantity().negate();
        BigDecimal executedPrice = event.getPrice();

        BigDecimal newQty = currentQty.add(executedQty);
        BigDecimal newEntryPrice = currentEntryPrice;

        // Logic for Average Entry Price
        if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            newEntryPrice = BigDecimal.ZERO;
        } else if (currentQty.signum() == 0) {
            newEntryPrice = executedPrice;
        } else if (currentQty.signum() == executedQty.signum()) {
            // Increasing position (Long + Long or Short + Short)
            BigDecimal totalCost = currentEntryPrice.multiply(currentQty.abs())
                    .add(executedPrice.multiply(executedQty.abs()));
            newEntryPrice = totalCost.divide(newQty.abs(), 8, RoundingMode.HALF_UP);
        } else {
            // Partial close or Reversal
            if (currentQty.abs().compareTo(executedQty.abs()) < 0) {
                // Reversal: old position closed, new one opened in opposite direction
                newEntryPrice = executedPrice;
            }
            // If partial close without reversal, entryPrice remains the same
        }

        return current.toBuilder()
                .netQuantity(newQty)
                .avgEntryPrice(newEntryPrice)
                .updatedAt(Instant.now())
                .build();
    }
}
