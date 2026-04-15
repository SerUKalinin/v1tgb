package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.time.Instant;

public interface RiskEvent {
    Instant getTimestamp();
    String getEventId();

    record TradeExecuted(
        String eventId,
        String symbol,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal realizedPnl,
        Instant timestamp
    ) implements RiskEvent {
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public String getEventId() { return eventId; }
    }

    record PriceUpdated(
        String eventId,
        String symbol,
        BigDecimal newPrice,
        Instant timestamp
    ) implements RiskEvent {
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public String getEventId() { return eventId; }
    }
}
