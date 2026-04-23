package com.tradingbot.infrastructure.outbox;

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
    public boolean isAlreadyProcessed(UUID eventId) {
        return repository.existsByEventId(eventId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsProcessed(UUID eventId, String consumerName) {
        ProcessedEventEntity entity = ProcessedEventEntity.builder()
                .eventId(eventId)
                .processedAt(Instant.now())
                .consumerName(consumerName)
                .build();
        repository.save(entity);
        log.debug("[IDEMPOTENCY] Event {} marked as processed by {}", eventId, consumerName);
    }
}
