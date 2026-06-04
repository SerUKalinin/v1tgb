package com.tradingbot.infrastructure.outbox;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.fasterxml.jackson.databind.ObjectMapper;import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
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
    public void publishEvent(ExecutionContext context, String aggregateType, String eventType, Object payload) {
        try {
            // Outbox = прямое отражение ExecutionContext
            // eventId берется напрямую из attempt.executionId() (SSOT)
            UUID eventId = context.attempt().executionId();

            // Проверка на дубликат перед вставкой (идемпотентность по executionId)
            if (outboxRepository.existsById(eventId)) {
                log.info("[OUTBOX-SKIP] Event with executionId {} already exists. Skipping.", eventId);
                return;
            }

            long nextSequence = outboxRepository.getNextSequenceNumber(context.aggregateId());

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(eventId)
                    .eventId(eventId)
                    .aggregateId(context.aggregateId())
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.NEW)
                    .sequenceNumber(nextSequence)
                    .createdAt(Instant.now())
                    .schemaVersion(1)
                    .signalId(context.signalId())
                    .orderId(UUID.fromString(context.business().orderId()))
                    .executionId(context.attempt().executionId())
                    .causationId(context.attempt().causationId())
                    .correlationId(context.identity().correlationId())
                    .build();
            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("[OUTBOX-STRICT-ERROR] Context: {}, Event: {}", context, eventType, e);
            throw new RuntimeException("Outbox publication failed due to identity/causality violation", e);
        }
    }}
