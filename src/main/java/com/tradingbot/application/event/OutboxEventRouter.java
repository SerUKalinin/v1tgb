package com.tradingbot.application.event;

import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Роутер событий Outbox.
 *
 * Отвечает за распределение событий из Outbox между соответствующими
 * обработчиками (consumers).
 *
 * Используется внутри OutboxProcessor для детерминированного dispatching
 * событий на основе их типа.
 */
@Slf4j
@Component
public class OutboxEventRouter {

    /**
     * Отсортированный список consumers.
     *
     * Порядок определяется через Spring @Order / Ordered.
     */
    private final List<OutboxConsumer> consumers;

    public OutboxEventRouter(List<OutboxConsumer> consumers) {
        List<OutboxConsumer> orderedConsumers =
                new ArrayList<>(consumers);

        AnnotationAwareOrderComparator.sort(orderedConsumers);

        this.consumers = List.copyOf(orderedConsumers);

        log.info(
                "[ROUTER] Initialized {} outbox consumers in deterministic order: {}",
                this.consumers.size(),
                this.consumers.stream()
                        .map(consumer ->
                                consumer.getClass().getSimpleName())
                        .toList()
        );
    }

    /**
     * Маршрутизирует событие соответствующим consumer'ам по типу события.
     *
     * Все consumers, поддерживающие данный тип события, выполняются
     * последовательно в установленном порядке.
     *
     * @param event событие из Outbox
     * @throws Exception если consumer выбросил исключение
     */
    public void route(OutboxEventEntity event) throws Exception {
        String eventType = event.getEventType();

        log.debug(
                "[ROUTER] Маршрутизация события типа: {}",
                eventType
        );

        boolean handled = false;

        for (OutboxConsumer consumer : consumers) {
            if (!consumer.supports(eventType)) {
                continue;
            }

            log.debug(
                    "[ROUTER] Dispatch {} -> {}",
                    eventType,
                    consumer.getClass().getSimpleName()
            );

            consumer.consume(event);

            handled = true;
        }

        if (!handled) {
            log.warn(
                    "[ROUTER] Не найден обработчик для типа события: {}",
                    eventType
            );
        }
    }
}