package com.tradingbot.infrastructure.outbox;

import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.IdentityContext;
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
    public void publishEvent(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business, String aggregateType, String eventType, Object payload) {
        try {
            // STRICT IDENTITY + CAUSALITY CHECK
            if (attempt.executionId() == null) {
                throw new IllegalStateException("OUTBOX WRITE RULE VIOLATION: executionId is null. Event: " + eventType);
            }
            if (attempt.causationId() == null) {
                throw new IllegalStateException("STRICT CAUSALITY VIOLATION: causationId is null. Event: " + eventType);
            }

            UUID eventId = UUID.randomUUID();
            long nextSequence = outboxRepository.getNextSequenceNumber(identity.aggregateId());

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(eventId)
                    .eventId(eventId) // PRIMARY LOGICAL IDENTITY
                    .aggregateId(identity.aggregateId())
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.NEW)
                    .sequenceNumber(nextSequence)
                    .createdAt(Instant.now())
                    .schemaVersion(1)
                    .signalId(identity.signalId())
                    .orderId(UUID.fromString(business.orderId()))
                    .executionId(attempt.executionId())
                    .causationId(attempt.causationId()) // MUST reference previous eventId
                    .correlationId(identity.correlationId())
                    .build();

            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("[OUTBOX-STRICT-ERROR] Identity: {}, Attempt: {}, Event: {}", identity, attempt, eventType, e);
            throw new RuntimeException("Outbox publication failed due to identity/causality violation", e);
        }
    }
}
