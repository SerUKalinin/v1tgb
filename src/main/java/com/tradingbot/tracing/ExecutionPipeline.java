package com.tradingbot.tracing;

import java.util.UUID;
import java.util.Map;

/**
 * PHASE PIPELINE
 * Управляет переходами между фазами контекста.
 * Каждый метод возвращает НОВЫЙ объект.
 */
public final class ExecutionPipeline {
    
    private ExecutionPipeline() {}

    /**
     * PHASE 0: Root Identity
     */
    public static IdentityContext createIdentity(UUID signalId) {
        return IdentityContext.of(signalId);
    }
    /**
     * PHASE 1: Lifecycle Attempt (From Identity)
     */
    public static ExecutionAttemptContext startAttempt(IdentityContext identity, UUID causationId) {
        // Identity используется как триггер фазы
        return ExecutionAttemptContext.firstAttempt(causationId);
    }

    /**
     * PHASE 2: Business State (From Attempt)
     */
    public static BusinessContext createBusiness(ExecutionAttemptContext attempt, String orderId, Map<String, Object> params) {
        return BusinessContext.of(orderId, params);
    }

    /**
     * PHASE 3: Outbox Projection (Final Merge for Persistence)
     */
    public static OutboxProjection project(IdentityContext id, ExecutionAttemptContext exec, BusinessContext biz) {
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
