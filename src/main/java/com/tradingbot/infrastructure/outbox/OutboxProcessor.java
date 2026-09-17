package com.tradingbot.infrastructure.outbox;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.event.OutboxEventRouter;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.ExecutionLogger;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor
        implements ApplicationContextAware {

    private final OutboxEventRepository outboxRepository;

    private final OutboxEventRouter router;

    private final OutboxRetryPolicy retryPolicy;

    private final DeadLetterAlertService alertService;

    private final ExecutionLogger executionLogger;

    private final SystemStateManager systemStateManager;

    private ApplicationContext applicationContext;

    private final Set<UUID> activeAggregates =
            ConcurrentHashMap.newKeySet();

    @Value("${app.outbox.enabled:true}")
    private boolean enabled;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    public void shutdown() {

        this.shuttingDown = true;

        log.info(
                "[OUTBOX] Получен сигнал завершения. Остановка процессора..."
        );
    }

    @Override
    public void setApplicationContext(
            ApplicationContext applicationContext
    ) {

        this.applicationContext =
                applicationContext;
    }

    /**
     * Spring proxy для корректного применения
     * @Transactional при self invocation.
     */
    private OutboxProcessor self() {

        return applicationContext.getBean(
                OutboxProcessor.class
        );
    }

    @Scheduled(
            fixedDelayString =
                    "${app.outbox.scan-interval:500}"
    )
    public void scheduledProcess() {

        if (!enabled || shuttingDown) {
            return;
        }

        if (!systemStateManager.isTradingEnabled()) {
            return;
        }

        processOutbox();
    }

    /**
     * Основной цикл обработки outbox.
     */
    public void processOutbox() {

        try {

            List<OutboxEventEntity> events =
                    self().claimBatch();

            if (events.isEmpty()) {
                return;
            }

            log.info(
                    "[OUTBOX] Processing batch of {} events.",
                    events.size()
            );

            Map<UUID, List<OutboxEventEntity>>
                    groupedEvents =
                    events.stream()
                            .collect(
                                    java.util.stream.Collectors.groupingBy(
                                            OutboxEventEntity::getAggregateId,
                                            LinkedHashMap::new,
                                            java.util.stream.Collectors.toList()
                                    )
                            );

            for (Map.Entry<UUID, List<OutboxEventEntity>> entry
                    : groupedEvents.entrySet()) {

                UUID aggregateId =
                        entry.getKey();

                List<OutboxEventEntity> aggregateEvents =
                        entry.getValue();

                if (shuttingDown) {
                    break;
                }

                OutboxEventEntity firstEvent =
                        aggregateEvents.get(0);

                executionLogger.log(
                        com.tradingbot.tracing.ExecutionLogFactory.forEvent(
                                firstEvent.getExecutionId(),
                                firstEvent.getOrderId(),
                                firstEvent.getSignalId(),
                                com.tradingbot.tracing.ExecutionEventType.OUTBOX_CLAIM_START,
                                "CLAIMED",
                                "Claimed outbox aggregate " +
                                        aggregateId
                        )
                );

                if (!activeAggregates.add(
                        aggregateId
                )) {

                    log.debug(
                            "[OUTBOX] Aggregate {} is already being processed, " +
                                    "skipping batch",
                            aggregateId
                    );

                    continue;
                }

                try {

                    if (outboxRepository.existsUnprocessedBefore(
                            aggregateId,
                            aggregateEvents
                                    .get(0)
                                    .getSequenceNumber()
                    )) {

                        log.warn(
                                "[OUTBOX] Gap detected for aggregate {}, " +
                                        "skipping batch",
                                aggregateId
                        );

                        continue;
                    }

                    log.debug(
                            "[OUTBOX] Processing {} events for aggregate {}",
                            aggregateEvents.size(),
                            aggregateId
                    );

                    for (OutboxEventEntity event :
                            aggregateEvents) {

                        if (shuttingDown) {
                            break;
                        }

                        try {

                            self().processSingleEvent(
                                    event
                            );

                        } catch (Exception e) {

                            log.error(
                                    "[OUTBOX] Event {} processing failed. " +
                                            "Business transaction rolled back. " +
                                            "Scheduling retry.",
                                    event.getId(),
                                    e
                            );

                            self().handleFailureInternal(
                                    event.getId(),
                                    e.getMessage()
                            );
                        }
                    }

                } finally {

                    activeAggregates.remove(
                            aggregateId
                    );
                }
            }

        } catch (
                InvalidDataAccessResourceUsageException e
        ) {

            if (shuttingDown
                    || (
                    e.getMessage() != null
                            && e.getMessage().contains(
                            "outbox_events"
                    )
            )) {

                log.debug(
                        "[OUTBOX] Shutdown or missing table"
                );

            } else {

                log.error(
                        "[OUTBOX] Database error: {}",
                        e.getMessage(),
                        e
                );
            }

        } catch (Exception e) {

            if (shuttingDown) {

                log.debug(
                        "[OUTBOX] Error during shutdown: {}",
                        e.getMessage()
                );

            } else {

                log.error(
                        "[OUTBOX] Unexpected error: {}",
                        e.getMessage(),
                        e
                );
            }
        }
    }

    /**
     * Обработка одного Outbox-события.
     *
     * ВАЖНО:
     *
     * router.route(...)
     * +
     * status = PROCESSED
     *
     * находятся в одной transaction.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void processSingleEvent(
            OutboxEventEntity event
    ) throws Exception {

        if (event.getStatus() == OutboxStatus.DEAD) {
            return;
        }

        if (!TransactionSynchronizationManager
                .isActualTransactionActive()) {

            log.error(
                    "[OUTBOX] No transaction active for event {}",
                    event.getId()
            );

            throw new IllegalStateException(
                    "Transaction required for outbox processing"
            );
        }

        if (event.getAttemptCount() > 5) {

            log.warn(
                    "[OUTBOX] High attempt count for event {}",
                    event.getId()
            );
        }

        /*
         * JPA entity конвертируется в чистую модель
         * до передачи в application.
         */
        router.route(
                OutboxEventMapper.toDomain(
                        event
                )
        );

        finalizeProcessedInCurrentTransaction(
                event.getId()
        );

        log.info(
                "[OUTBOX] Event {} processed successfully",
                event.getId()
        );
    }

    /**
     * Финализация текущей обработки.
     *
     * НЕ REQUIRES_NEW.
     */
    private void finalizeProcessedInCurrentTransaction(
            UUID eventId
    ) {

        OutboxEventEntity event =
                outboxRepository.findById(eventId)
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Outbox event not found: " +
                                                eventId
                                )
                        );

        event.setStatus(
                OutboxStatus.PROCESSED
        );

        event.setProcessedAt(
                Instant.now()
        );

        event.setLastError(null);
        event.setLockOwner(null);
        event.setLockedUntil(null);
        event.setClaimedBy(null);
        event.setClaimedAt(null);
        event.setLeaseUntil(null);

        event.setUpdatedAt(
                Instant.now()
        );

        outboxRepository.saveAndFlush(
                event
        );
    }

    /**
     * Retry/DLQ mutation после rollback
     * исходной business transaction.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void handleFailureInternal(
            UUID eventId,
            String errorMessage
    ) {

        outboxRepository
                .findById(eventId)
                .ifPresent(event -> {

                    if (event.getStatus()
                            == OutboxStatus.PROCESSED
                            || event.getStatus()
                            == OutboxStatus.DEAD) {

                        return;
                    }

                    int retries =
                            event.getRetryCount() + 1;

                    event.setRetryCount(
                            retries
                    );

                    event.setUpdatedAt(
                            Instant.now()
                    );

                    event.setLastError(
                            errorMessage
                    );

                    if (retryPolicy.shouldRetry(event)) {

                        event.setStatus(
                                OutboxStatus.FAILED
                        );

                        long delaySeconds =
                                (long) Math.pow(
                                        2,
                                        retries
                                );

                        event.setNextAttemptAt(
                                Instant.now()
                                        .plusSeconds(
                                                delaySeconds
                                        )
                        );

                        event.setLockOwner(null);
                        event.setLockedUntil(null);
                        event.setClaimedBy(null);
                        event.setClaimedAt(null);
                        event.setLeaseUntil(null);

                    } else {

                        event.setStatus(
                                OutboxStatus.DEAD
                        );

                        alertService.sendAlert(
                                event
                        );
                    }

                    outboxRepository.saveAndFlush(
                            event
                    );
                });
    }

    /**
     * Захват batch.
     *
     * Отдельная transaction завершается
     * до начала business processing.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public List<OutboxEventEntity> claimBatch() {

        String ownerId =
                java.lang.management.ManagementFactory
                        .getRuntimeMXBean()
                        .getName();

        Instant now =
                Instant.now();

        Instant lockUntil =
                now.plusSeconds(30);

        List<OutboxEventEntity> events =
                outboxRepository.claimBatchWithLock(
                        50,
                        now
                );

        events.forEach(event -> {

            event.setStatus(
                    OutboxStatus.PROCESSING
            );

            event.setLockOwner(
                    ownerId
            );

            event.setLockedUntil(
                    lockUntil
            );

            event.setClaimedBy(
                    ownerId
            );

            event.setClaimedAt(
                    now
            );

            event.setLeaseUntil(
                    lockUntil
            );

            event.setAttemptCount(
                    event.getAttemptCount() + 1
            );

            event.setUpdatedAt(
                    now
            );
        });

        return outboxRepository
                .saveAllAndFlush(events);
    }
}