package com.tradingbot.tracing;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OutboxEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * UNIFIED EXECUTION CONTEXT (SSOT)
 *
 * Единственный носитель identity в системе.
 *
 * Запрещено:
 * - распаковывать identity в UUID вне соответствующего context;
 * - передавать identity отдельно от context;
 * - восстанавливать execution identity из persistence entity.
 */
public record ExecutionContext(
        IdentityContext identity,
        ExecutionAttemptContext attempt,
        BusinessContext business
) {

    public ExecutionContext {

        Objects.requireNonNull(
                identity,
                "identity cannot be null"
        );

        Objects.requireNonNull(
                attempt,
                "attempt cannot be null"
        );

        Objects.requireNonNull(
                business,
                "business cannot be null"
        );
    }

    public static ExecutionContext of(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business
    ) {

        return new ExecutionContext(
                identity,
                attempt,
                business
        );
    }

    public static ExecutionContext restore(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business
    ) {

        return new ExecutionContext(
                identity,
                attempt,
                business
        );
    }

    public ExecutionContext withTransportRetry() {

        return new ExecutionContext(
                identity,
                attempt.withTransportRetry(),
                business
        );
    }

    public ExecutionContext nextBusinessAttempt() {

        return new ExecutionContext(
                identity,
                attempt.nextBusinessAttempt(),
                business
        );
    }

    public ExecutionContext withNextStep(UUID eventId) {

        return new ExecutionContext(
                identity,
                attempt.nextStep(eventId),
                business
        );
    }

    @Deprecated(
            since = "Use context from SignalEvent or Outbox recovery"
    )
    public static ExecutionContext of(UUID signalId) {

        return new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.of(signalId),
                BusinessContext.empty()
        );
    }

    public ExecutionContext withBusiness(
            BusinessContext business
    ) {

        return new ExecutionContext(
                identity,
                attempt,
                business
        );
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
     * Восстановление ExecutionContext из чистой
     * Outbox-модели.
     *
     * STRICT RESTORE:
     * identity берётся только из явных identity-полей.
     *
     * aggregateId не используется как fallback,
     * потому что aggregateId предназначен для routing.
     */
    public static ExecutionContext from(
            OutboxEvent event
    ) {

        Objects.requireNonNull(
                event,
                "OutboxEvent cannot be null"
        );

        Objects.requireNonNull(
                event.signalId(),
                "STRICT RESTORE FAILURE: signalId is missing in OutboxEvent"
        );

        Objects.requireNonNull(
                event.executionId(),
                "STRICT RESTORE FAILURE: executionId is missing in OutboxEvent"
        );

        Objects.requireNonNull(
                event.causationId(),
                "STRICT RESTORE FAILURE: causationId is missing in OutboxEvent"
        );

        return restore(
                new IdentityContext(
                        event.signalId(),
                        event.correlationId() != null
                                ? event.correlationId()
                                : event.signalId()
                ),
                ExecutionAttemptContext.recover(
                        event.causationId(),
                        event.executionId(),
                        event.attemptCount()
                ),
                BusinessContext.of(
                        event.orderId() != null
                                ? event.orderId().toString()
                                : "UNKNOWN"
                )
        );
    }

    /**
     * Создание контекста на основе доменного объекта Order.
     */
    public static ExecutionContext of(Order order) {

        Objects.requireNonNull(
                order,
                "order cannot be null"
        );

        UUID executionId =
                order.getExecutionId();

        if (executionId == null) {
            throw new IllegalStateException(
                    "Order must already have executionId assigned"
            );
        }

        UUID signalId =
                order.getSignalId();

        Objects.requireNonNull(
                signalId,
                "Order must already have signalId assigned"
        );

        UUID causationId = signalId;

        int attempt =
                order.getExecutionAttempts() > 0
                        ? order.getExecutionAttempts()
                        : 1;

        return restore(
                new IdentityContext(
                        signalId,
                        signalId
                ),
                ExecutionAttemptContext.recover(
                        causationId,
                        executionId,
                        attempt
                ),
                BusinessContext.of(
                        order.getId().toString()
                )
        );
    }
}