package com.tradingbot.tracing;

import java.util.UUID;
import java.util.Objects;

/**
 * LIFECYCLE CONTEXT
 * Отслеживает конкретную попытку выполнения в рамках цепочки событий.
 */
public record ExecutionAttemptContext(
    UUID executionId,
    UUID causationId,
    int attemptNumber
) {
    public ExecutionAttemptContext {
        Objects.requireNonNull(executionId, "executionId cannot be null");
        Objects.requireNonNull(causationId, "causationId cannot be null");
    }

    public static ExecutionAttemptContext of(UUID signalId) {
        return new ExecutionAttemptContext(signalId, signalId, 1);
    }

    public static ExecutionAttemptContext forOrder(UUID orderId, int attemptNumber) {
        UUID executionId = IdentityFactory.deriveExecution(orderId, attemptNumber);
        return new ExecutionAttemptContext(executionId, orderId, attemptNumber);
    }

    public static ExecutionAttemptContext recover(UUID signalId) {        return new ExecutionAttemptContext(signalId, signalId, 1);
    }

    public static ExecutionAttemptContext firstAttempt(UUID causationId) {
        UUID executionId = IdentityFactory.derive(causationId, "execution-1");
        return new ExecutionAttemptContext(executionId, causationId, 1);
    }

    public ExecutionAttemptContext nextAttempt(UUID aggregateId) {
        int nextNumber = this.attemptNumber + 1;
        UUID executionId = IdentityFactory.deriveExecution(aggregateId, nextNumber);
        return new ExecutionAttemptContext(executionId, aggregateId, nextNumber);
    }
    public ExecutionAttemptContext nextStep(UUID stepCausationId) {
        UUID stepExecutionId = IdentityFactory.derive(stepCausationId, "step-" + (this.attemptNumber));
        return new ExecutionAttemptContext(stepExecutionId, stepCausationId, this.attemptNumber);
    }
}
