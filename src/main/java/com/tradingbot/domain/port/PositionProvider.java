package com.tradingbot.domain.port;

public interface PositionProvider {
    boolean hasOpenPosition(String symbol);
}
