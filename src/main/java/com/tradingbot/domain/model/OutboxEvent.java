package com.tradingbot.domain.model;

import java.util.UUID;

/**
 * Чистое представление outbox-события,
 * доступное application/domain слоям.
 *
 * Не содержит JPA/Spring/infrastructure dependencies.
 *
 * OutboxEventEntity остаётся исключительно
 * persistence-моделью infrastructure-слоя.
 */
public record OutboxEvent(
        UUID id,
        UUID aggregateId,
        Long sequenceNumber,
        String aggregateType,
        String eventType,
        String payload,
        UUID signalId,
        UUID orderId,
        UUID executionId,
        UUID causationId,
        UUID correlationId,
        UUID eventId,
        int attemptCount
) {
}