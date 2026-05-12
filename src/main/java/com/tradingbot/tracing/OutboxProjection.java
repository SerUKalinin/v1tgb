package com.tradingbot.tracing;

import java.util.UUID;

/**
 * PHASE 3: Outbox Projection
 * Результирующая проекция для записи в БД (Outbox).
 * Это DTO, а не контекст выполнения.
 */
public record OutboxProjection(
    UUID signalId,
    UUID correlationId,
    UUID executionId,
    UUID causationId,
    String orderId,
    int attemptNumber
) {}
