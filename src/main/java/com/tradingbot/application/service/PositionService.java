package com.tradingbot.application.service;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.Trade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class PositionService {

    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    public void applyTrade(Trade trade) { /* реализация из истории */ }
    public boolean hasOpenPosition(String symbol) { return positions.containsKey(symbol) && positions.get(symbol).getQuantity().compareTo(BigDecimal.ZERO) > 0; }
    public BigDecimal calculatePnL(String symbol, BigDecimal currentPrice) {

        Position position = positions.get(symbol);

        if (position == null || position.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal quantity = position.getQuantity();

        // (current - entry) * qty
        return currentPrice
                .subtract(entryPrice)
                .multiply(quantity)
                .setScale(8, RoundingMode.HALF_UP);
    }
}