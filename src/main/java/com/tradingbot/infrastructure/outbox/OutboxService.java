package com.tradingbot.infrastructure.outbox;

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
    public void publishEvent(UUID aggregateId, String aggregateType, String eventType, Object payload) {
        try {
            // Гарантированная блокировка и получение номера (FOR UPDATE внутри репозитория)
            long nextSequence = outboxRepository.getNextSequenceNumber(aggregateId);

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(aggregateId != null ? aggregateId : UUID.randomUUID())
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.NEW)
                    .sequenceNumber(nextSequence)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("[OUTBOX-ERROR] Failed to publish event {} for aggregate {}", eventType, aggregateId, e);
            throw new RuntimeException("Outbox publication failed", e);
        }
    }
}
