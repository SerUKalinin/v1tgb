package com.tradingbot.domain.market;

public interface MarketDataProvider {
    MarketSnapshot getSnapshot(String symbol);
}
