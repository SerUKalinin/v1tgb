package com.tradingbot.tracing;

import java.util.Map;
import java.util.UUID;

/**
 * PHASE PIPELINE
 *
 * Управляет переходами между фазами ExecutionContext.
 * Каждый метод возвращает новый immutable object.
 */
public final class ExecutionPipeline {

    private ExecutionPipeline() {
    }

    /**
     * PHASE 0: Root Identity
     *
     * signalId уже должен существовать
     * на внешней границе системы.
     */
    public static IdentityContext createIdentity(
            UUID signalId
    ) {
        return IdentityContext.of(signalId);
    }

    /**
     * PHASE 1: Lifecycle Attempt
     *
     * Canonical execution identity:
     *
     * orderId
     *     ↓
     * executionId
     *
     * Важно:
     * executionId больше НЕ строится от signalId.
     */
    public static ExecutionAttemptContext startAttempt(
            IdentityContext identity,
            UUID orderId
    ) {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "identity cannot be null"
            );
        }

        if (orderId == null) {
            throw new IllegalArgumentException(
                    "orderId cannot be null"
            );
        }

        return ExecutionAttemptContext.firstAttemptForOrder(
                orderId
        );
    }

    /**
     * PHASE 2: Business State
     */
    public static BusinessContext createBusiness(
            ExecutionAttemptContext attempt,
            String orderId,
            Map<String, Object> params
    ) {
        if (attempt == null) {
            throw new IllegalArgumentException(
                    "attempt cannot be null"
            );
        }

        return BusinessContext.of(
                orderId,
                params
        );
    }

    /**
     * PHASE 3: Outbox Projection
     *
     * executionId сохраняется из ExecutionAttemptContext
     * и не пересоздаётся.
     */
    public static OutboxProjection project(
            IdentityContext id,
            ExecutionAttemptContext exec,
            BusinessContext biz
    ) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "identity cannot be null"
            );
        }

        if (exec == null) {
            throw new IllegalArgumentException(
                    "execution context cannot be null"
            );
        }

        if (biz == null) {
            throw new IllegalArgumentException(
                    "business context cannot be null"
            );
        }

        return new OutboxProjection(
                id.signalId(),
                id.correlationId(),
                exec.executionId(),
                exec.causationId(),
                biz.orderId(),
                exec.attemptNumber()
        );
    }
}
