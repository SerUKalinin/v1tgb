package com.tradingbot.infrastructure.outbox;

import com.tradingbot.application.event.OutboxEventRouter;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor implements ApplicationContextAware {

private final OutboxEventRepository outboxRepository;
    private final OutboxEventRouter router;
    private final OutboxRetryPolicy retryPolicy;
    private final DeadLetterAlertService alertService;
    private final com.tradingbot.tracing.ExecutionLogger executionLogger;
    private ApplicationContext applicationContext;
    private final java.util.Set<UUID> activeAggregates = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Value("${app.outbox.enabled:true}")
    private boolean enabled;

    private volatile boolean shuttingDown = false;

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
        log.info("[OUTBOX] Получен сигнал завершения. Остановка процессора...");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private OutboxProcessor self() {
        return applicationContext.getBean(OutboxProcessor.class);
    }

    @Scheduled(fixedDelayString = "${app.outbox.scan-interval:500}")
    public void scheduledProcess() {
        if (!enabled || shuttingDown) return;
        processOutbox();
    }

    public void processOutbox() {
        try {
            // 1. Захват батча в отдельной транзакции
            List<OutboxEventEntity> events = self().claimBatch();
            if (events.isEmpty()) return;

            log.info("[OUTBOX] Processing batch of {} events.", events.size());

            // Группируем события по aggregate_id для последовательной обработки
            Map<UUID, List<OutboxEventEntity>> groupedEvents = events.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            OutboxEventEntity::getAggregateId,
                            java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.toList()
                    ));

            for (Map.Entry<UUID, List<OutboxEventEntity>> entry : groupedEvents.entrySet()) {
                UUID aggregateId = entry.getKey();

                executionLogger.log(com.tradingbot.tracing.ExecutionLogFactory.forEvent(
                        aggregateId.toString(),
                        aggregateId.toString(),
                        aggregateId.toString(),
                        com.tradingbot.tracing.ExecutionEventType.OUTBOX_CLAIM_START,
                        "CLAIMED",
                        "Claimed outbox aggregate " + aggregateId
                ));
                if (shuttingDown) break;

                // Guard: если aggregateId уже обрабатывается другим потоком в этом инстансе — skip
                if (!activeAggregates.add(aggregateId)) {
                    log.debug("[OUTBOX] Aggregate {} is already being processed, skipping batch", aggregateId);
                    continue;
                }

                try {
                    List<OutboxEventEntity> aggregateEvents = entry.getValue();
                    
                    // Guard: Проверка на наличие "дыр" в последовательности
                    if (outboxRepository.existsUnprocessedBefore(aggregateId, aggregateEvents.get(0).getSequenceNumber())) {
                        log.warn("[OUTBOX] Gap detected for aggregate {}, skipping batch to maintain order", aggregateId);
                        continue;
                    }

                    log.debug("[OUTBOX] Processing {} events for aggregate {}", aggregateEvents.size(), aggregateId);                    
                    for (OutboxEventEntity event : aggregateEvents) {
                        if (shuttingDown) break;
                        self().processSingleEvent(event);
                    }
                } finally {
                    activeAggregates.remove(aggregateId);
                }
            }
        } catch (org.springframework.dao.InvalidDataAccessResourceUsageException e) {
            if (shuttingDown || (e.getMessage() != null && e.getMessage().contains("outbox_events"))) {
                log.debug("[OUTBOX] Table not found or shutting down (normal during context shutdown/init)");
            } else {
                log.error("[OUTBOX] Database error: {}", e.getMessage());
            }
        } catch (Exception e) {
            if (shuttingDown) {
                log.debug("[OUTBOX] Error during shutdown: {}", e.getMessage());
            } else {
                log.error("[OUTBOX] Unexpected error in processOutbox: {}", e.getMessage());
            }
        }
    }    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(OutboxEventEntity event) {
        if (event.getStatus() == OutboxStatus.DEAD) return;

        // Защита от транзакционных аномалий
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("[OUTBOX] CRITICAL: No transaction active in processSingleEvent for event {}", event.getId());
            throw new IllegalStateException("Transaction required for outbox processing");
        }

        // Защита от retry storm
        if (event.getAttemptCount() > 5) {
            log.warn("[OUTBOX] High attempt count ({}) for event {}. Applying backoff.",
                    event.getAttemptCount(), event.getId());
        }

        log.debug("[OUTBOX] Starting event {}. TX: {}", event.getId(),
                TransactionSynchronizationManager.getCurrentTransactionName());

        try {
            // 1. Выполнение бизнес-логики (Routing)
            router.route(event);

            // 2. Фиксация успеха (в отдельной транзакции, чтобы избежать конфликтов с блокировками)
            self().finalizeProcessed(event.getId());
            log.info("[OUTBOX] Event {} processed successfully", event.getId());

        } catch (Exception e) {
            log.error("[OUTBOX] Error processing event {}: {}", event.getId(), e.getMessage());
            // Гарантированно сохраняем ошибку и инкрементируем счетчик в новой транзакции
            self().handleFailureInternal(event.getId(), e.getMessage());
        }    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEventEntity> claimBatch() {
        String ownerId = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        Instant now = Instant.now();
        Instant lockUntil = now.plusSeconds(30);

        // Использует native query с FOR UPDATE SKIP LOCKED
        List<OutboxEventEntity> events = outboxRepository.claimBatchWithLock(50, now);
        events.forEach(e -> {
            e.setStatus(OutboxStatus.PROCESSING);
            e.setLockOwner(ownerId);
            e.setLockedUntil(lockUntil);
            e.setClaimedBy(ownerId);
            e.setClaimedAt(now);
            e.setLeaseUntil(lockUntil);
            e.setAttemptCount(e.getAttemptCount() + 1);
            e.setUpdatedAt(now);

            log.info("[OUTBOX_LEASE_ACQUIRED] eventId={} claimedBy={} claimedAt={} leaseUntil={} executionId={}",
                    e.getId(), ownerId, now, lockUntil, e.getAggregateId());
        });
        return outboxRepository.saveAllAndFlush(events);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailureInternal(UUID eventId, String errorMessage) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setUpdatedAt(Instant.now());
            event.setLastError(errorMessage);

            if (retryPolicy.shouldRetry(event)) {
                event.setStatus(OutboxStatus.FAILED);
                // Exponential backoff: 2, 4, 8, 16, 32... seconds
                long delaySeconds = (long) Math.pow(2, retries);
                event.setNextAttemptAt(Instant.now().plusSeconds(delaySeconds));
                log.info("[OUTBOX] Event {} marked for retry ({}) in {}s", eventId, retries, delaySeconds);
            } else {
                event.setStatus(OutboxStatus.DEAD);
                log.error("[OUTBOX] Event {} moved to DEAD letter (retries exhausted). Reason: {}", eventId, errorMessage);
                alertService.sendAlert(event);
            }
            outboxRepository.saveAndFlush(event);
        });
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeProcessed(UUID eventId) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            event.setLastError(null);
            outboxRepository.saveAndFlush(event);
        });
    }}
