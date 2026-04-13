package com.tradingbot.infrastructure.client;

import com.tradingbot.domain.model.MarketData;
import java.math.BigDecimal;

public interface MarketDataClient {
    BigDecimal getPrice(String symbol);
    MarketData getMarketData(String symbol);
}