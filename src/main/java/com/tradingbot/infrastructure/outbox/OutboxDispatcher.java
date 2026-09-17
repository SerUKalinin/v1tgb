package com.tradingbot.infrastructure.outbox;

import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Диспетчер outbox-событий.
 *
 * Legacy infrastructure dispatcher.
 *
 * Основной production path сейчас использует
 * OutboxEventRouter через OutboxProcessor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final List<OutboxConsumer> consumers;

    /**
     * Диспетчеризация события в подходящие handlers.
     */
    public void dispatch(
            OutboxEventEntity event
    ) throws Exception {

        if (event == null) {
            throw new IllegalArgumentException(
                    "OutboxEventEntity cannot be null"
            );
        }

        OutboxEvent domainEvent =
                OutboxEventMapper.toDomain(
                        event
                );

        boolean handled = false;

        for (OutboxConsumer consumer :
                consumers) {

            if (!consumer.supports(
                    domainEvent.eventType()
            )) {

                continue;
            }

            consumer.consume(
                    domainEvent
            );

            handled = true;
        }

        if (!handled) {

            log.warn(
                    "[OUTBOX-DISPATCHER] " +
                            "No consumer found for event type: {}",
                    domainEvent.eventType()
            );
        }
    }
}