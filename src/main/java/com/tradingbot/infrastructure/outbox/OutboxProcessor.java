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

    /**
     * Репозиторий outbox-событий для выборки, блокировки и обновления статусов.
     */
    private final OutboxEventRepository outboxRepository;

    /**
     * Роутер, отвечающий за маршрутизацию событий к соответствующим обработчикам.
     */
    private final OutboxEventRouter router;

    /**
     * Политика повторных попыток обработки событий.
     */
    private final OutboxRetryPolicy retryPolicy;

    /**
     * Сервис уведомлений о попадании событий в DLQ (dead letter queue).
     */
    private final DeadLetterAlertService alertService;

    /**
     * Логгер исполнительных событий (для трассировки обработки outbox).
     */
    private final com.tradingbot.tracing.ExecutionLogger executionLogger;

    /**
     * Spring ApplicationContext для получения проксированного self-bean.
     */
    private ApplicationContext applicationContext;

    /**
     * Набор агрегатов, которые сейчас обрабатываются в текущем инстансе,
     * используется для предотвращения параллельной обработки одного агрегата.
     */
    private final java.util.Set<UUID> activeAggregates =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Флаг включения outbox-процессора (из конфигурации приложения).
     */
    @Value("${app.outbox.enabled:true}")
    private boolean enabled;

    /**
     * Флаг завершения работы системы (graceful shutdown).
     */
    private volatile boolean shuttingDown = false;

    /**
     * Вызывается при завершении работы приложения.
     * Переводит процессор в режим остановки.
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        this.shuttingDown = true;
        log.info("[OUTBOX] Получен сигнал завершения. Остановка процессора...");
    }

    /**
     * Устанавливает Spring ApplicationContext для доступа к proxy-bean.
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Возвращает проксированный self-bean для корректной работы @Transactional методов.
     */
    private OutboxProcessor self() {
        return applicationContext.getBean(OutboxProcessor.class);
    }

    /**
     * Периодический запуск обработки outbox-событий.
     */
    @Scheduled(fixedDelayString = "${app.outbox.scan-interval:500}")
    public void scheduledProcess() {
        if (!enabled || shuttingDown) return;
        processOutbox();
    }

    /**
     * Основной цикл обработки outbox.
     * Забирает батч событий, группирует по агрегатам и обрабатывает последовательно.
     */
    public void processOutbox() {
        try {
            // 1. Захват батча в отдельной транзакции
            List<OutboxEventEntity> events = self().claimBatch();
            if (events.isEmpty()) return;

            log.info("[OUTBOX] Processing batch of {} events.", events.size());

            // Группировка по aggregateId для последовательной обработки
            Map<UUID, List<OutboxEventEntity>> groupedEvents = events.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            OutboxEventEntity::getAggregateId,
                            java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.toList()
                    ));

            for (Map.Entry<UUID, List<OutboxEventEntity>> entry : groupedEvents.entrySet()) {
                UUID aggregateId = entry.getKey();

                executionLogger.log(com.tradingbot.tracing.ExecutionLogFactory.forEvent(
                        aggregateId,
                        aggregateId,
                        aggregateId,
                        com.tradingbot.tracing.ExecutionEventType.OUTBOX_CLAIM_START,
                        "CLAIMED",
                        "Claimed outbox aggregate " + aggregateId
                ));

                if (shuttingDown) break;

                // Проверка на параллельную обработку одного агрегата
                if (!activeAggregates.add(aggregateId)) {
                    log.debug("[OUTBOX] Aggregate {} is already being processed, skipping batch", aggregateId);
                    continue;
                }

                try {
                    List<OutboxEventEntity> aggregateEvents = entry.getValue();

                    // Проверка целостности последовательности событий
                    if (outboxRepository.existsUnprocessedBefore(
                            aggregateId,
                            aggregateEvents.get(0).getSequenceNumber())) {
                        log.warn("[OUTBOX] Gap detected for aggregate {}, skipping batch", aggregateId);
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
                log.debug("[OUTBOX] Shutdown or missing table");
            } else {
                log.error("[OUTBOX] Database error: {}", e.getMessage());
            }
        } catch (Exception e) {
            if (shuttingDown) {
                log.debug("[OUTBOX] Error during shutdown: {}", e.getMessage());
            } else {
                log.error("[OUTBOX] Unexpected error: {}", e.getMessage());
            }
        }
    }

    /**
     * Обработка одного outbox-события в новой транзакции.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(OutboxEventEntity event) {
        if (event.getStatus() == OutboxStatus.DEAD) return;

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.error("[OUTBOX] No transaction active for event {}", event.getId());
            throw new IllegalStateException("Transaction required for outbox processing");
        }

        if (event.getAttemptCount() > 5) {
            log.warn("[OUTBOX] High attempt count for event {}", event.getId());
        }

        try {
            router.route(event);

            self().finalizeProcessed(event.getId());
            log.info("[OUTBOX] Event {} processed successfully", event.getId());

        } catch (Exception e) {
            log.error("[OUTBOX] Error processing event {}: {}", event.getId(), e.getMessage());
            self().handleFailureInternal(event.getId(), e.getMessage());
        }
    }

    /**
     * Захват батча событий из БД с блокировкой.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEventEntity> claimBatch() {
        String ownerId = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        Instant now = Instant.now();
        Instant lockUntil = now.plusSeconds(30);

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
        });

        return outboxRepository.saveAllAndFlush(events);
    }

    /**
     * Обработка ошибки события и управление retry / DLQ логикой.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailureInternal(UUID eventId, String errorMessage) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            int retries = event.getRetryCount() + 1;
            event.setRetryCount(retries);
            event.setUpdatedAt(Instant.now());
            event.setLastError(errorMessage);

            if (retryPolicy.shouldRetry(event)) {
                event.setStatus(OutboxStatus.FAILED);
                long delaySeconds = (long) Math.pow(2, retries);
                event.setNextAttemptAt(Instant.now().plusSeconds(delaySeconds));
            } else {
                event.setStatus(OutboxStatus.DEAD);
                alertService.sendAlert(event);
            }

            outboxRepository.saveAndFlush(event);
        });
    }

    /**
     * Финализация успешно обработанного события.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeProcessed(UUID eventId) {
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxStatus.PROCESSED);
            event.setProcessedAt(Instant.now());
            event.setLastError(null);
            outboxRepository.saveAndFlush(event);
        });
    }
}