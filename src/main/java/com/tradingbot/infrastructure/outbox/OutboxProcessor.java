package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor implements ApplicationContextAware {

    private final OutboxEventRepository outboxRepository;
    private final OutboxDispatcher dispatcher;
    private final OutboxRetryPolicy retryPolicy;
    private final IdempotencyService idempotencyService;
    private final DeadLetterAlertService alertService;
    private ApplicationContext applicationContext;
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private OutboxProcessor self() {
        return applicationContext.getBean(OutboxProcessor.class);
    }

    @Scheduled(fixedDelayString = "${app.outbox.scan-interval:500}")
    public void processOutbox() {
        List<OutboxEventEntity> events = claimBatch();
        if (events.isEmpty()) return;

        log.debug("[OUTBOX] Processing batch of {} events", events.size());

        for (OutboxEventEntity event : events) {
            self().processSingleEvent(event);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(OutboxEventEntity event) {
        if (event.getStatus() == OutboxStatus.DEAD) {
            log.warn("[OUTBOX] Skipping DEAD event {}", event.getId());
            return;
        }

        try {
            if (idempotencyService.isAlreadyProcessed(event.getId())) {
                log.info("[OUTBOX] Event {} already processed globally. Skipping.", event.getId());
                finalizeProcessed(event);
                return;
            }

            dispatcher.dispatch(event);
            idempotencyService.markAsProcessed(event.getId(), "GlobalOutboxProcessor");
            finalizeProcessed(event);
            
        } catch (Exception e) {
            log.error("[OUTBOX] Failed to process event {}: {}", event.getId(), e.getMessage());
            handleFailureInternal(event.getId(), e.getMessage());
        }
    }

    private void finalizeProcessed(OutboxEventEntity event) {
        event.setStatus(OutboxStatus.PROCESSED);
        event.setProcessedAt(Instant.now());
        event.setLastError(null);
        outboxRepository.save(event);
    }

    @Transactional
    protected List<OutboxEventEntity> claimBatch() {
        List<OutboxEventEntity> events = outboxRepository.claimBatchWithLock(50);
        events.forEach(e -> {
            e.setStatus(OutboxStatus.PROCESSING);
            e.setUpdatedAt(Instant.now());
        });
        return outboxRepository.saveAllAndFlush(events);
    }

    private void handleFailureInternal(UUID eventId, String errorMessage) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.setRetryCount(event.getRetryCount() + 1);
            event.setUpdatedAt(Instant.now());
            event.setLastError(errorMessage);

            if (retryPolicy.shouldRetry(event)) {
                event.setStatus(OutboxStatus.FAILED);
                log.info("[OUTBOX] Event {} marked for retry ({})", eventId, event.getRetryCount());
            } else {
                event.setStatus(OutboxStatus.DEAD);
                log.error("[OUTBOX] Event {} moved to DEAD letter (retries exhausted). Reason: {}", eventId, errorMessage);
                alertService.sendAlert(event);
            }
            outboxRepository.save(event);        });
    }
    @Deprecated
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markProcessed(UUID eventId) {
        // Use processSingleEvent logic instead
    }

    @Deprecated
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void handleFailure(UUID eventId) {
        // Use processSingleEvent logic instead
    }
}