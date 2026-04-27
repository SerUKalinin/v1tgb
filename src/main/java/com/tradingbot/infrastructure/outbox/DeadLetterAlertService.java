package com.tradingbot.infrastructure.outbox;

import com.tradingbot.application.service.AdminNotificationService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterAlertService {

    private final AdminNotificationService notificationService;
    private final MeterRegistry meterRegistry;

    @Async
    public void sendAlert(OutboxEventEntity event) {
        log.error("[DLQ-ALERT] Event {} moved to DEAD letter queue. AggregateId: {}, Type: {}, Retries: {}", 
                event.getId(), event.getAggregateId(), event.getEventType(), event.getRetryCount());

        // Increment metric
        meterRegistry.counter("outbox.dead_letter.total", 
                "event_type", event.getEventType(),
                "aggregate_type", event.getAggregateType()
        ).increment();

        // Send Telegram notification
        String message = String.format(
            "💀 *OUTBOX DEAD LETTER ALERT*\n\n" +
            "🆔 *Event ID:* `%s`\n" +
            "📦 *Aggregate:* `%s` (%s)\n" +
            "📝 *Type:* `%s`\n" +
            "🔄 *Retries:* %d\n" +
            "❌ *Reason:* %s",
            event.getId(),
            event.getAggregateId(),
            event.getAggregateType(),
            event.getEventType(),
            event.getRetryCount(),
            event.getLastError() != null ? event.getLastError() : "Unknown error"
        );

        try {
            notificationService.notifyCriticalError("OUTBOX-DLQ", message);
        } catch (Exception e) {
            log.error("[DLQ-ALERT] Failed to send notification for event {}", event.getId(), e);
        }
    }
}
