package com.tradingbot.application.service.order;

import java.util.UUID;

/**
 * Строго типизированное доменное событие создания ордера.
 *
 * <p>Используется как контракт между application-слоем и Outbox-механизмом
 * для гарантии сохранения ключевых идентификаторов исполнения:
 * signalId, orderId, executionId.</p>
 *
 * <p>Является immutable DTO и не содержит бизнес-логики.</p>
 */
public record OrderCreatedEvent(
        UUID signalId,
        UUID orderId,
        UUID executionId
) {

    /**
     * Канонический конструктор с проверкой обязательных полей.
     *
     * <p>Обеспечивает инварианты целостности события до его попадания в Outbox.</p>
     *
     * @throws IllegalArgumentException если хотя бы один обязательный идентификатор null
     */
    public OrderCreatedEvent {
        if (signalId == null) throw new IllegalArgumentException("signalId is required");
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        if (executionId == null) throw new IllegalArgumentException("executionId is required");
    }
}