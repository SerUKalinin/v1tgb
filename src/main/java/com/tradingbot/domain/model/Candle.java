package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
@AllArgsConstructor
public class Candle {
    String symbol;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    BigDecimal volume;
    Instant openTime;
    Instant closeTime;
    boolean isClosed;

    public Instant openTime() {
        return openTime;
    }

    public static Candle of(String symbol, BigDecimal open, BigDecimal high, BigDecimal low, 
                           BigDecimal close, BigDecimal volume, Instant openTime, 
                           Instant closeTime, boolean isClosed) {
        return new Candle(symbol, open, high, low, close, volume, openTime, closeTime, isClosed);
    }
}
