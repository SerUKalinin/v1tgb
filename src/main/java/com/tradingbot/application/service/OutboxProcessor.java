package com.tradingbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.domain.event.OrderReadyForExecutionEvent;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Canonical Outbox Processor — the ONLY scheduled component that reads outbox events.
 *
 * Replaces:
 * - application.service.OutboxProcessor (deleted)
 * - application.service.OutboxDispatcher (deleted)
 * - infrastructure.execution.OutboxProcessor (deleted)
 *
 * Design:
 * - Polls PENDING events every second
 * - Publishes OrderReadyForExecutionEvent via Spring ApplicationEventPublisher
 * - OrderExecutionListener picks it up with @TransactionalEventListener(AFTER_COMMIT)
 * - On success: marks SENT; on failure: marks FAILED (no retry loop here — watchdog handles retries)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void processPendingEvents() {
        List<OutboxEventEntity> pendingEvents = outboxRepository.findByStatus("PENDING");

        for (OutboxEventEntity event : pendingEvents) {
            processEvent(event);
        }
    }

    private void processEvent(OutboxEventEntity event) {
        try {
            log.info("[Outbox] Processing event {} type={} order={}",
                    event.getEventId(), event.getType(), event.getClientOrderId());

            // Currently only ORDER_APPROVED events are stored in outbox
            if ("ORDER_APPROVED".equals(event.getType())) {
                // We publish an OrderReadyForExecutionEvent — OMS has already validated
                // and committed the order. This event triggers execution.
                OrderReadyForExecutionEvent readyEvent = new OrderReadyForExecutionEvent(
                        event.getEventId(), // Using eventId as orderId placeholder if needed, but usually it's clientOrderId
                        event.getClientOrderId()
                );
                eventPublisher.publishEvent(readyEvent);
                markSent(event);
            }

        } catch (Exception e) {
            log.error("[Outbox] Failed to process event {}", event.getEventId(), e);
            markFailed(event, e.getMessage());
        }
    }

    private void markSent(OutboxEventEntity event) {
        event.setStatus("SENT");
        event.setProcessedAt(Instant.now());
        outboxRepository.save(event);
    }

    private void markFailed(OutboxEventEntity event, String reason) {
        event.setStatus("FAILED");
        event.setProcessedAt(Instant.now());
        outboxRepository.save(event);
        log.error("[Outbox] Event {} marked FAILED: {}", event.getEventId(), reason);
    }
}