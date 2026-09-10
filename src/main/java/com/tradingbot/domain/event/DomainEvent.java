package com.tradingbot.domain.event;

import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.BusinessContext;
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
     * @param identity контекст идентичности (signal/order/aggregate)
     * @param attempt контекст попытки исполнения
     * @param business бизнес-контекст события
     * @param schemaVersion версия схемы события
     */
    protected DomainEvent(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business,
            int schemaVersion
    ) {
        this.identity = identity;
        this.attempt = attempt;
        this.business = business;
        this.timestamp = Instant.now();
        this.schemaVersion = schemaVersion;
        this.eventId = IdentityFactory.deriveEventId(
                attempt.executionId(),
                this.getClass().getSimpleName()
        );
    }

    /**
     * Тип события в доменной системе.
     *
     * @return строковый идентификатор типа события
     */
    public abstract String getEventType();
}