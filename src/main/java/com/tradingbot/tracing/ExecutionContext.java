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

    public static ExecutionContext restore(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business) {
        return new ExecutionContext(identity, attempt, business);
    }

    public ExecutionContext withTransportRetry() {
        return new ExecutionContext(identity, attempt.withTransportRetry(), business);
    }

    public ExecutionContext nextBusinessAttempt() {
        return new ExecutionContext(identity, attempt.nextBusinessAttempt(), business);
    }

    public ExecutionContext withNextStep(UUID eventId) {
        return new ExecutionContext(identity, attempt.nextStep(eventId), business);
    }

    @Deprecated(since = "Use context from SignalEvent or Outbox recovery")
    public static ExecutionContext of(UUID signalId) {
        return new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.of(signalId),
                BusinessContext.empty()
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
     * STRICT RESTORE: Использует только явные поля идентичности из сущности.
     * Fallback на aggregateId запрещён, так как он предназначен для роутинга.
     */
    public static ExecutionContext from(com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity event) {
        Objects.requireNonNull(event.getSignalId(), "STRICT RESTORE FAILURE: signalId is missing in OutboxEvent");
        Objects.requireNonNull(event.getExecutionId(), "STRICT RESTORE FAILURE: executionId is missing in OutboxEvent");
        Objects.requireNonNull(event.getCausationId(), "STRICT RESTORE FAILURE: causationId is missing in OutboxEvent");

        return restore(
                new IdentityContext(
                        event.getSignalId(),
                        event.getCorrelationId() != null ? event.getCorrelationId() : event.getSignalId()
                ),
                ExecutionAttemptContext.recover(
                        event.getCausationId(),
                        event.getExecutionId(),
                        event.getAttemptCount()
                ),
                BusinessContext.of(event.getOrderId() != null ? event.getOrderId().toString() : "UNKNOWN")
        );
    }

    /**
     * Создание контекста на основе доменного объекта Order.
     */
    public static ExecutionContext of(Order order) {
        Objects.requireNonNull(order, "order cannot be null");
        UUID executionId = order.getExecutionId();
        if (executionId == null) {
            throw new IllegalStateException("Order must already have executionId assigned");
        }
        UUID signalId = order.getSignalId();
        Objects.requireNonNull(signalId, "Order must already have signalId assigned");
        UUID causationId = signalId;
        int attempt = order.getExecutionAttempts() > 0 ? order.getExecutionAttempts() : 1;
        return restore(
                new IdentityContext(signalId, signalId),
                ExecutionAttemptContext.recover(
                        causationId,
                        executionId,
                        attempt
                ),
                BusinessContext.of(order.getId().toString())
        );
    }
}
