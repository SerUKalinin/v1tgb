package com.tradingbot.application.service.order;

import java.util.UUID;

/**
 * Строго типизированное событие создания ордера.
 * Используется для предотвращения потери signalId при сериализации в Outbox.
 */
public record OrderCreatedEvent(
    UUID signalId,
    UUID orderId,
    UUID executionId
) {
    public OrderCreatedEvent {
        if (signalId == null) throw new IllegalArgumentException("signalId is required");
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        if (executionId == null) throw new IllegalArgumentException("executionId is required");
    }
}
