package com.tradingbot.infrastructure.outbox;

import com.tradingbot.tracing.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void publishEvent(ExecutionContext context, String aggregateType, String eventType, Object payload) {
        try {
            UUID eventId = UUID.randomUUID();
            long nextSequence = outboxRepository.getNextSequenceNumber(context.aggregateId());

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(eventId)
                    .eventId(eventId) // Explicit eventId
                    .aggregateId(context.aggregateId())
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.NEW)
                    .sequenceNumber(nextSequence)
                    .createdAt(Instant.now())
                    .schemaVersion(1)
                    .signalId(context.signalId())
                    .orderId(context.orderId())
                    .executionId(context.executionId())
                    .causationId(context.causationId())
                    .correlationId(context.correlationId())
                    .build();

            outboxRepository.save(event);
        } catch (Exception e) {            log.error("[OUTBOX-ERROR] Failed to publish event {} for context {}", eventType, context, e);
            throw new RuntimeException("Outbox publication failed", e);
        }
    }
}
