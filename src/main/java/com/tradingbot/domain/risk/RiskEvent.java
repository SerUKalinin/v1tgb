package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.time.Instant;

public interface RiskEvent {
    Instant getTimestamp();
    String getEventId();
    default String getSymbol() { return null; }

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
        @Override public String getSymbol() { return symbol; }
    }

    record PriceUpdated(
        String eventId,
        String symbol,
        BigDecimal newPrice,
        Instant timestamp
    ) implements RiskEvent {
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public String getEventId() { return eventId; }
        @Override public String getSymbol() { return symbol; }
    }

    record TradingHalted(
        String eventId,
        String reason,
        Instant timestamp
    ) implements RiskEvent {
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public String getEventId() { return eventId; }
    }

    record CapitalReserved(
        String eventId,
        java.util.UUID orderId,
        BigDecimal amount,
        Instant timestamp
    ) implements RiskEvent {
        public CapitalReserved(String eventId, java.util.UUID orderId, BigDecimal amount) {
            this(eventId, orderId, amount, Instant.now());
        }
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public String getEventId() { return eventId; }
    }

    record CapitalReleased(
        String eventId,
        java.util.UUID orderId,
        BigDecimal amount,
        String reason,
        Instant timestamp
    ) implements RiskEvent {
        public CapitalReleased(String eventId, java.util.UUID orderId, BigDecimal amount, String reason) {
            this(eventId, orderId, amount, reason, Instant.now());
        }
        @Override public Instant getTimestamp() { return timestamp; }
        @Override public String getEventId() { return eventId; }
    }
}
