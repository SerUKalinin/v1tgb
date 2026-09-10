package com.tradingbot.infrastructure.outbox;

import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Сервис обработки событий, попавших в Dead Letter Queue (DLQ).
 *
 * <p>Отвечает за фиксацию критических ошибок обработки outbox-событий,
 * а также за уведомление администраторов и сбор метрик.</p>
 *
 * <p>Используется как последний уровень защиты для событий,
 * которые не удалось обработать после всех retry-итераций.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterAlertService {

    private final AdminNotificationService notificationService;
    private final MeterRegistry meterRegistry;

    /**
     * Отправляет alert при попадании события в DLQ.
     *
     * <p>Выполняет:
     * <ul>
     *   <li>логирование критической ошибки</li>
     *   <li>инкремент метрик DLQ</li>
     *   <li>отправку уведомления администратору</li>
     * </ul>
     * </p>
     *
     * @param event событие outbox, попавшее в DLQ
     */
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