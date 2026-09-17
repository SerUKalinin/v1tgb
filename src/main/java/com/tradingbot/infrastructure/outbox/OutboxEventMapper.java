package com.tradingbot.infrastructure.outbox;

import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;

/**
 * Infrastructure mapper:
 *
 * OutboxEventEntity -> чистая OutboxEvent модель.
 *
 * Только infrastructure знает о JPA entity.
 */
public final class OutboxEventMapper {

    private OutboxEventMapper() {
    }

    public static OutboxEvent toDomain(OutboxEventEntity entity) {

        if (entity == null) {
            throw new IllegalArgumentException(
                    "OutboxEventEntity cannot be null"
            );
        }

        return new OutboxEvent(
                entity.getId(),
                entity.getAggregateId(),
                entity.getSequenceNumber(),
                entity.getAggregateType(),
                entity.getEventType(),
                entity.getPayload(),
                entity.getSignalId(),
                entity.getOrderId(),
                entity.getExecutionId(),
                entity.getCausationId(),
                entity.getCorrelationId(),
                entity.getEventId(),
                entity.getAttemptCount()
        );
    }
}