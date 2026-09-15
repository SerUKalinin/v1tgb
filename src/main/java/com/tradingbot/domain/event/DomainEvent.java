package com.tradingbot.domain.event;

import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Базовое доменное событие системы.
 *
 * <p>Определяет единый контракт для всех событий доменного слоя,
 * обеспечивая трассируемость, идентичность и версионирование.</p>
 *
 * <p>Гарантирует наличие:
 * <ul>
 *     <li>уникального eventId</li>
 *     <li>контекста идентичности исполнения</li>
 *     <li>бизнес-контекста</li>
 *     <li>контекста попытки выполнения (execution attempt)</li>
 *     <li>timestamp создания события</li>
 * </ul>
 */
@Getter
public abstract class DomainEvent {

    private final UUID eventId;
    private final IdentityContext identity;
    private final ExecutionAttemptContext attempt;
    private final BusinessContext business;
    private final Instant timestamp;
    private final int schemaVersion;

    /**
     * Базовый конструктор доменного события.
     *
     * @param identity контекст идентичности
     * @param attempt контекст попытки исполнения
     * @param business бизнес-контекст события
     * @param eventType канонический тип события
     * @param schemaVersion версия схемы события
     */
    protected DomainEvent(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business,
            String eventType,
            int schemaVersion
    ) {
        if (identity == null) {
            throw new IllegalArgumentException("identity cannot be null");
        }

        if (attempt == null) {
            throw new IllegalArgumentException("attempt cannot be null");
        }

        if (business == null) {
            throw new IllegalArgumentException("business cannot be null");
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be null or blank");
        }

        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }

        this.identity = identity;
        this.attempt = attempt;
        this.business = business;
        this.timestamp = Instant.now();
        this.schemaVersion = schemaVersion;

        this.eventId = IdentityFactory.deriveEventId(
                attempt.executionId(),
                eventType
        );
    }

    /**
     * Тип события в доменной системе.
     *
     * @return строковый идентификатор типа события
     */
    public abstract String getEventType();
}