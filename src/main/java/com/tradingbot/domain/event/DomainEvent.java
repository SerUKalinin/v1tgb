package com.tradingbot.domain.event;

import com.tradingbot.tracing.ExecutionContext;
import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

/**
 * <h1>Base Domain Event</h1>
 * 
 * <p>Формальный контракт для всех событий системы.
 * Гарантирует наличие полной идентичности выполнения и метаданных.
 */
@Getter
public abstract class DomainEvent {
    private final UUID eventId;
    private final ExecutionContext context;
    private final Instant timestamp;
    private final int schemaVersion;

    protected DomainEvent(ExecutionContext context, int schemaVersion) {
        this.eventId = UUID.randomUUID();
        this.context = context;
        this.timestamp = Instant.now();
        this.schemaVersion = schemaVersion;
    }

    public abstract String getEventType();
}
