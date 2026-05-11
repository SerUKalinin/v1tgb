package com.tradingbot.infrastructure.outbox;

import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isAlreadyProcessed(UUID eventId) {        return repository.existsById(eventId);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsProcessed(UUID eventId, String consumerName) {
        if (eventId == null) {
            log.warn("[IDEMPOTENCY] Attempted to mark null eventId as processed");
            return;
        }
        try {
            ProcessedEventEntity entity = new ProcessedEventEntity();
            entity.setEventId(eventId);
            entity.setProcessedAt(Instant.now());
            entity.setConsumerName(consumerName);
            
            repository.saveAndFlush(entity);
            log.debug("[IDEMPOTENCY] Event {} marked as processed by {}", eventId, consumerName);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("[IDEMPOTENCY] Event already processed eventId={}", eventId);
        }
    }
    }