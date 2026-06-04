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
        
        // SECURITY GUARD: Запрет совпадения executionId и causationId (обычно это signalId)
        // Это гарантирует, что executionId всегда является производным (derived).
        if (executionId.equals(causationId)) {
            throw new IllegalStateException("BROKEN IDENTITY CONTRACT: executionId must be derived and never equal to causationId/signalId");
        }
    }

    public static ExecutionAttemptContext of(UUID causationId) {
        return firstAttempt(causationId);
    }

    public static ExecutionAttemptContext forOrder(UUID orderId, UUID executionId, int attemptNumber) {
        Objects.requireNonNull(executionId, "executionId cannot be null when recovering from order");
        return new ExecutionAttemptContext(executionId, orderId, attemptNumber);
    }

    public static ExecutionAttemptContext recover(UUID causationId, UUID executionId, int attempt) {
        return new ExecutionAttemptContext(executionId, causationId, attempt);
    }

    public static ExecutionAttemptContext firstAttempt(UUID causationId) {
        UUID executionId = IdentityFactory.derive(causationId, "execution-1");
        return new ExecutionAttemptContext(executionId, causationId, 1);
    }

    /**
     * TRANSPORT RETRY
     * Используется при технических сбоях (сеть, БД, Outbox).
     * Сохраняет тот же executionId, чтобы гарантировать идемпотентность на стороне получателя.
     */
    public ExecutionAttemptContext withTransportRetry() {
        return new ExecutionAttemptContext(this.executionId, this.causationId, this.attemptNumber + 1);
    }

    /**
     * BUSINESS RETRY
     * Используется при логических повторах (например, Watchdog перезапускает зависший ордер).
     * Генерирует НОВЫЙ детерминированный executionId для нового цикла выполнения.
     */
    public ExecutionAttemptContext nextBusinessAttempt() {
        int nextNumber = this.attemptNumber + 1;
        // Используем causationId (обычно orderId или signalId) для детерминированной генерации
        UUID nextExecutionId = IdentityFactory.deriveExecution(this.causationId, nextNumber);
        return new ExecutionAttemptContext(nextExecutionId, this.causationId, nextNumber);
    }

    public ExecutionAttemptContext nextStep(UUID stepCausationId) {
        UUID stepExecutionId = IdentityFactory.derive(stepCausationId, "step-" + (this.attemptNumber));
        return new ExecutionAttemptContext(stepExecutionId, stepCausationId, this.attemptNumber);
    }
}
