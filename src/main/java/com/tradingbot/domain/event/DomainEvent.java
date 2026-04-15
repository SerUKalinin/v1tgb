package com.tradingbot.domain.event;

import lombok.Getter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
public abstract class DomainEvent {
    private final UUID eventId;
    private final Instant timestamp;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID();
        this.timestamp = Instant.now();
    }
}
