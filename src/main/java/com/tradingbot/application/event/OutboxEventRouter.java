package com.tradingbot.application.event;

import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Роутер событий Outbox.
 *
 * Получает только чистую OutboxEvent-модель.
 * JPA entity не пересекает application boundary.
 */
@Slf4j
@Component
public class OutboxEventRouter {

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
     * Маршрутизация события по eventType.
     */
    public void route(OutboxEvent event) throws Exception {

        if (event == null) {
            throw new IllegalArgumentException(
                    "OutboxEvent cannot be null"
            );
        }

        String eventType = event.eventType();

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