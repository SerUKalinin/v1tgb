package com.tradingbot.tracing;

import java.util.Objects;
import java.util.UUID;

/**
 * LIFECYCLE CONTEXT
 *
 * Отслеживает конкретную попытку выполнения
 * в рамках одной execution lifecycle.
 *
 * Canonical identity:
 *
 * orderId
 *     ↓
 * executionId
 */
public record ExecutionAttemptContext(
        UUID executionId,
        UUID causationId,
        int attemptNumber
) {

    public ExecutionAttemptContext {
        Objects.requireNonNull(
                executionId,
                "executionId cannot be null"
        );

        Objects.requireNonNull(
                causationId,
                "causationId cannot be null"
        );

        if (executionId.equals(causationId)) {
            throw new IllegalStateException(
                    "BROKEN IDENTITY CONTRACT: " +
                            "executionId must be derived and never equal " +
                            "to causationId"
            );
        }

        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "attemptNumber must be >= 1"
            );
        }
    }

    /**
     * Восстановление уже существующей execution lifecycle.
     *
     * executionId не генерируется повторно.
     */
    public static ExecutionAttemptContext recover(
            UUID causationId,
            UUID executionId,
            int attempt
    ) {
        return new ExecutionAttemptContext(
                executionId,
                causationId,
                attempt
        );
    }

    /**
     * Создание первой execution attempt для Order.
     *
     * Canonical identity:
     *
     * executionId =
     * IdentityFactory.deriveExecution(orderId, 1)
     */
    public static ExecutionAttemptContext firstAttemptForOrder(
            UUID orderId
    ) {
        Objects.requireNonNull(
                orderId,
                "orderId cannot be null"
        );

        UUID executionId =
                IdentityFactory.deriveExecution(
                        orderId,
                        1
                );

        return new ExecutionAttemptContext(
                executionId,
                orderId,
                1
        );
    }

    /**
     * TRANSPORT RETRY
     *
     * Технический retry сохраняет тот же executionId.
     */
    public ExecutionAttemptContext withTransportRetry() {
        return new ExecutionAttemptContext(
                this.executionId,
                this.causationId,
                this.attemptNumber + 1
        );
    }

    /**
     * BUSINESS RETRY
     *
     * Новый business attempt получает
     * новый детерминированный executionId.
     */
    public ExecutionAttemptContext nextBusinessAttempt() {
        int nextNumber = this.attemptNumber + 1;

        /*
         * Для Order lifecycle causationId здесь является
         * родительским идентификатором следующей попытки.
         */
        UUID nextExecutionId =
                IdentityFactory.deriveExecution(
                        this.causationId,
                        nextNumber
                );

        return new ExecutionAttemptContext(
                nextExecutionId,
                this.causationId,
                nextNumber
        );
    }

    /**
     * Следующий event/step внутри той же execution lifecycle.
     *
     * executionId остаётся неизменным.
     */
    public ExecutionAttemptContext nextStep(
            UUID stepCausationId
    ) {
        Objects.requireNonNull(
                stepCausationId,
                "stepCausationId cannot be null"
        );

        return new ExecutionAttemptContext(
                this.executionId,
                stepCausationId,
                this.attemptNumber
        );
    }

    /**
     * Явное создание первого attempt,
     * когда executionId уже известен.
     *
     * Используется для restore/explicit identity paths.
     */
    public static ExecutionAttemptContext firstAttempt(
            UUID executionId,
            UUID causationId
    ) {
        return new ExecutionAttemptContext(
                executionId,
                causationId,
                1
        );
    }

    /**
     * LEGACY COMPATIBILITY BRIDGE
     *
     * Старые тесты и legacy builders могут передавать
     * только causationId.
     *
     * Новый canonical production-код НЕ должен использовать
     * этот overload.
     *
     * Чтобы не возвращать старую неправильную схему,
     * сначала детерминированно создаётся synthetic orderId:
     *
     * causationId
     *      ↓
     * synthetic orderId
     *      ↓
     * executionId
     */
    @Deprecated(
            since = "1.0",
            forRemoval = true
    )
    public static ExecutionAttemptContext firstAttempt(
            UUID causationId
    ) {
        Objects.requireNonNull(
                causationId,
                "causationId cannot be null"
        );

        UUID syntheticOrderId =
                IdentityFactory.deriveOrder(
                        causationId
                );

        return firstAttemptForOrder(
                syntheticOrderId
        );
    }

    /**
     * LEGACY COMPATIBILITY ALIAS
     *
     * Оставлен для старых тестов и legacy context builders.
     *
     * Новый код должен использовать:
     *
     * firstAttemptForOrder(orderId)
     */
    @Deprecated(
            since = "1.0",
            forRemoval = true
    )
    public static ExecutionAttemptContext of(
            UUID causationId
    ) {
        return firstAttempt(
                causationId
        );
    }
}