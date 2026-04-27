package com.tradingbot.application.event;

import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Роутер событий Outbox.
 * Находится в слое application и отвечает за маршрутизацию событий к конкретным обработчикам.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventRouter {

    private final List<OutboxConsumer> consumers;

    /**
     * Маршрутизирует событие соответствующему потребителю.
     * 
     * @param event сущность события из Outbox
     * @throws Exception если обработчик выбросил исключение (для retry-логики)
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
