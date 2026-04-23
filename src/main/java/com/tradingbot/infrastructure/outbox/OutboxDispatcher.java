package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    private final List<OutboxConsumer> consumers;

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
