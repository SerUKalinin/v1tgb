package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;

public interface OutboxConsumer {
    boolean supports(String eventType);
    void consume(OutboxEventEntity event) throws Exception;
}
