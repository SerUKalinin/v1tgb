package com.tradingbot.infrastructure.outbox;

import com.tradingbot.application.event.OutboxEventRouter;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor implements ApplicationContextAware {

    private final OutboxEventRepository outboxRepository;
    private final OutboxEventRouter router;
    private final OutboxRetryPolicy retryPolicy;
    private final IdempotencyService idempotencyService;
    private final DeadLetterAlertService alertService;
    private ApplicationContext applicationContext;
    
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
    public void processOutbox() {
        if (shuttingDown) {
            return;
        }
        
        List<OutboxEventEntity> events = claimBatch();
        if (events.isEmpty()) return;

        log.debug("[OUTBOX] Обработка батча из {} событий", events.size());

        for (OutboxEventEntity event : events) {
            if (shuttingDown) break;
            self().processSingleEvent(event);
        }
    }
    /**
     * Обрабатывает одиночное событие в отдельной транзакции.
     * 
     * ПОРЯДОК ОПЕРАЦИЙ (Гарантия идемпотентности):
     * 1. Проверка в таблице processed_events (idempotency check).
     * 2. Выполнение бизнес-логики (router.route) -> Включает сетевой вызов к бирже.
     * 3. Запись в processed_events (markAsProcessed).
     * 4. Обновление статуса самого события (finalizeProcessed).
     * 
     * Если система упадет на шаге 2, событие останется в статусе PROCESSING/FAILED и будет переповторено.
     * Если система упадет на шаге 3 или 4, при повторе сработает шаг 1.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(OutboxEventEntity event) {
        if (event.getStatus() == OutboxStatus.DEAD) {
            log.warn("[OUTBOX] Пропуск DEAD события {}", event.getId());
            return;
        }

        try {
            // 1. Проверка на дубликат (Idempotency Check)
            if (idempotencyService.isAlreadyProcessed(event.getId())) {
                log.info("[OUTBOX] Событие {} уже было успешно обработано ранее. Пропуск.", event.getId());
                finalizeProcessed(event);
                return;
            }

            // 2. Выполнение (Execution) - может содержать сетевые вызовы
            router.route(event);

            // 3. Фиксация успешной обработки (Idempotency Commit)
            // Выполняется в той же транзакции, что и обновление статуса ордера (если хендлер транзакционен)
            idempotencyService.markAsProcessed(event.getId(), "GlobalOutboxProcessor");

            // 4. Завершение
            finalizeProcessed(event);
            
        } catch (Exception e) {
            log.error("[OUTBOX] Ошибка при обработке события {}: {}", event.getId(), e.getMessage());
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