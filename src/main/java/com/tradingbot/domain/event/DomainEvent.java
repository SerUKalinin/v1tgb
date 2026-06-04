package com.tradingbot.domain.event;

import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.IdentityFactory;
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
    private final IdentityContext identity;
    private final ExecutionAttemptContext attempt;
    private final BusinessContext business;
    private final Instant timestamp;
    private final int schemaVersion;

    protected DomainEvent(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business, int schemaVersion) {
        this.identity = identity;
        this.attempt = attempt;
        this.business = business;
        this.timestamp = Instant.now();
        this.schemaVersion = schemaVersion;
        this.eventId = IdentityFactory.deriveEventId(attempt.executionId(), this.getClass().getSimpleName());
    }

    public abstract String getEventType();
}
