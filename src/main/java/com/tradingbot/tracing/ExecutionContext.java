package com.tradingbot.tracing;

import com.tradingbot.domain.model.Order;
import java.util.Objects;
import java.util.UUID;

/**
 * UNIFIED EXECUTION CONTEXT (SSOT)
 * Единственный носитель identity в системе.
 * Запрещено: распаковка в UUID вне инфраструктурного слоя,
 * передача identity отдельно от context.
 */
public record ExecutionContext(
        IdentityContext identity,
        ExecutionAttemptContext attempt,
        BusinessContext business
) {
    public ExecutionContext {
        Objects.requireNonNull(identity, "identity cannot be null");
        Objects.requireNonNull(attempt, "attempt cannot be null");
        Objects.requireNonNull(business, "business cannot be null");
    }

    public static ExecutionContext of(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business) {
        return new ExecutionContext(identity, attempt, business);
    }

    public ExecutionContext withNextAttempt() {
        return new ExecutionContext(identity, attempt.nextAttempt(identity.aggregateId()), business);
    }

    public ExecutionContext withNextStep(UUID eventId) {        return new ExecutionContext(identity, attempt.nextStep(eventId), business);
    }

    public static ExecutionContext of(UUID signalId) {
        return new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.of(signalId),
                BusinessContext.of(signalId.toString())
        );
    }

    public ExecutionContext withBusiness(BusinessContext business) {
        return new ExecutionContext(identity, attempt, business);
    }

    public UUID correlationId() {
        return identity.correlationId();
    }

    public UUID aggregateId() {
        return identity.aggregateId();
    }

    public UUID signalId() {
        return identity.signalId();
    }

    public UUID causationId() {
        return attempt.causationId();
    }

    /**
     * Восстановление контекста из события Outbox.
     */
    public static ExecutionContext from(com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity event) {
        return new ExecutionContext(
                new IdentityContext(event.getSignalId(), event.getCorrelationId() != null ? event.getCorrelationId() : event.getSignalId()),
                new ExecutionAttemptContext(event.getExecutionId(), event.getCausationId(), event.getAttemptCount()),
                BusinessContext.of(event.getOrderId() != null ? event.getOrderId().toString() : "UNKNOWN")
        );
    }

    /**
     * Создание контекста на основе доменного объекта Order.
     */
    public static ExecutionContext of(Order order) {
        int attempt = order.getExecutionAttempts() > 0 ? order.getExecutionAttempts() : 1;
        return new ExecutionContext(
                new IdentityContext(order.getSignalId(), order.getSignalId()),
                ExecutionAttemptContext.forOrder(order.getId(), attempt),
                BusinessContext.of(order.getId().toString())
        );
    }}
