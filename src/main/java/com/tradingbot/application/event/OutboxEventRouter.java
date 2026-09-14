package com.tradingbot.application.event;

import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Роутер событий Outbox.
 *
 * Отвечает за распределение событий из Outbox между соответствующими обработчиками (consumers).
 * Является частью application слоя и реализует механизм dispatching событий
 * на основе их типа.
 *
 * Используется внутри OutboxProcessor для делегирования обработки бизнес-событий.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventRouter {

    /**
     * Список всех зарегистрированных consumers Outbox-событий.
     *
     * Spring автоматически инжектирует все бины, реализующие OutboxConsumer.
     */
    private final List<OutboxConsumer> consumers;

    /**
     * Маршрутизирует событие соответствующему consumer'у по типу события.
     *
     * Алгоритм:
     * 1. Определить тип события
     * 2. Найти все consumers, поддерживающие данный тип
     * 3. Передать событие в consume()
     * 4. Зафиксировать факт отсутствия обработчиков (warning)
     *
     * @param event сущность события из Outbox
     * @throws Exception если consumer выбросил исключение (для retry-логики на уровне OutboxProcessor)
     */
    public void route(OutboxEventEntity event) throws Exception {
        String eventType = event.getEventType();
        log.debug("[ROUTER] Маршрутизация события типа: {}", eventType);

        boolean handled = false;
        for (OutboxConsumer consumer : consumers) {
            if (consumer.supports(eventType)) {
                consumer.consume(event);
                handled = true;
            }
        }

        if (!handled) {
            log.warn("[ROUTER] Не найден обработчик для типа события: {}", eventType);
        }
    }
}