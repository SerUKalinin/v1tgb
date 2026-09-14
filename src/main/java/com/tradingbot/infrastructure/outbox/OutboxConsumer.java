package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;

/**
 * Контракт обработчика outbox-событий.
 *
 * <p>Определяет компонент, способный обрабатывать события,
 * извлечённые из outbox-таблицы.</p>
 *
 * <p>Реализация используется dispatcher'ом для маршрутизации
 * событий по типам.</p>
 */
public interface OutboxConsumer {

    /**
     * Проверяет, поддерживает ли обработчик данный тип события.
     *
     * @param eventType тип события из outbox
     * @return true если обработчик может обработать событие
     */
    boolean supports(String eventType);

    /**
     * Обрабатывает outbox-событие.
     *
     * @param event сущность outbox-события
     * @throws Exception при ошибке обработки события
     */
    void consume(OutboxEventEntity event) throws Exception;
}