package com.tradingbot.domain.model;

import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
public class CandleWindow {
    String symbol;
    List<Candle> candles;

    public List<BigDecimal> getClosePrices() {
        return candles.stream()
                .map(Candle::getClose)
                .toList();
    }

    public Candle getLast() {
        if (candles.isEmpty()) {
            throw new IllegalStateException("Candle window is empty for symbol: " + symbol);
        }
        return candles.get(candles.size() - 1);
    }

    public boolean isReady(int minRequiredSize) {
        return candles.size() >= minRequiredSize;
    }

    public int size() {
        return candles.size();
    }

    public String symbol() {
        return symbol;
    }

    public List<Candle> candles() {
        return candles;
    }

    public boolean isReady() {
        return !candles.isEmpty();
    }
}
