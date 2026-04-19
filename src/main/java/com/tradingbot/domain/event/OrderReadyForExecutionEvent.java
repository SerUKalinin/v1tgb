package com.tradingbot.domain.event;

import java.time.Instant;

/**
 * Событие, сигнализирующее о том, что ордер готов к исполнению.
 * Публикуется строго после фиксации транзакции создания ордера в БД.
 */
public record OrderReadyForExecutionEvent(
        String orderId,
        String clientOrderId,
        Instant createdAt
) {

    public OrderReadyForExecutionEvent(String orderId, String clientOrderId) {
        this(orderId, clientOrderId, Instant.now());
    }
}


