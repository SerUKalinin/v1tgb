package com.tradingbot.infrastructure.outbox;

import com.tradingbot.domain.model.OutboxEvent;

/**
 * Контракт обработчика outbox-событий.
 *
 * Persistence entity не выходит за infrastructure boundary.
 *
 * OutboxEventEntity используется только внутри infrastructure/persistence.
 * Consumers получают чистую OutboxEvent-модель.
 */
public interface OutboxConsumer {

    /**
     * Проверяет, поддерживает ли consumer данный тип события.
     *
     * @param eventType тип события
     * @return true если consumer обрабатывает данный тип
     */
    boolean supports(String eventType);

    /**
     * Обрабатывает outbox-событие.
     *
     * @param event чистая модель outbox-события
     * @throws Exception при ошибке обработки
     */
    void consume(OutboxEvent event) throws Exception;
}