package com.tradingbot.domain.market;

import com.tradingbot.domain.model.Candle;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Value
public class MarketSnapshot {
    String symbol;
    BigDecimal lastPrice;
    List<Candle> candles;
    Instant timestamp;
}
