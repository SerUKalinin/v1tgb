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

    public static ExecutionAttemptContext recover(UUID signalId) {
        return new ExecutionAttemptContext(signalId, signalId, 1);
    }

    public static ExecutionAttemptContext firstAttempt(UUID causationId) {
        return new ExecutionAttemptContext(UUID.randomUUID(), causationId, 1);
    }
    public ExecutionAttemptContext nextAttempt(UUID newCausationId) {
        return new ExecutionAttemptContext(UUID.randomUUID(), newCausationId, this.attemptNumber + 1);
    }
    
    public ExecutionAttemptContext nextStep(UUID newCausationId) {
        return new ExecutionAttemptContext(this.executionId, newCausationId, this.attemptNumber);
    }
}
