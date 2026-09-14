package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Диспетчер outbox-событий.
 *
 * <p>Отвечает за маршрутизацию событий из outbox-таблицы
 * к соответствующим обработчикам (OutboxConsumer).</p>
 *
 * <p>Поддерживает модель "fan-out": одно событие может быть
 * обработано несколькими consumers, если они его поддерживают.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final List<OutboxConsumer> consumers;

    /**
     * Диспетчеризация события в подходящие обработчики.
     *
     * <p>Итерирует по всем зарегистрированным consumers и вызывает
     * обработку для тех, кто поддерживает данный тип события.</p>
     *
     * @param event событие outbox
     * @throws Exception при ошибке обработки любого consumer'а
     */
    public void dispatch(OutboxEventEntity event) throws Exception {
        boolean handled = false;

        for (OutboxConsumer consumer : consumers) {
            if (consumer.supports(event.getEventType())) {
                consumer.consume(event);
                handled = true;
            }
        }

        if (!handled) {
            log.warn("[OUTBOX-DISPATCHER] No consumer found for event type: {}", event.getEventType());
        }
    }
}