package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Политика повторной обработки outbox-событий.
 *
 * <p>Отвечает за:
 * <ul>
 *   <li>ограничение количества повторных попыток обработки</li>
 *   <li>экспоненциальный backoff между попытками</li>
 *   <li>определение готовности события к повторной обработке</li>
 * </ul>
 *
 * <p>Используется OutboxProcessor для управления retry-циклом и предотвращения retry storm.
 */
@Component
public class OutboxRetryPolicy {

    /**
     * Максимальное количество повторных попыток обработки события.
     */
    private static final int MAX_RETRIES = 5;

    /**
     * Базовая задержка между попытками (в секундах) для exponential backoff.
     */
    private static final long BASE_DELAY_SECONDS = 2;

    /**
     * Проверяет, допустима ли повторная попытка обработки события.
     *
     * @param event outbox-событие
     * @return true если количество попыток не превышает лимит
     */
    public boolean shouldRetry(OutboxEventEntity event) {
        return event.getRetryCount() < MAX_RETRIES;
    }

    /**
     * Проверяет, готово ли событие к повторной обработке с учётом backoff.
     *
     * <p>Логика:
     * <ul>
     *   <li>NEW события готовы сразу</li>
     *   <li>FAILED события обрабатываются только после выдержки delay</li>
     * </ul>
     *
     * @param event outbox-событие
     * @return true если можно повторно обработать событие
     */
    public boolean isReadyForRetry(OutboxEventEntity event) {
        if (event.getStatus() == OutboxStatus.NEW) {
            return true;
        }
        if (event.getStatus() != OutboxStatus.FAILED) {
            return false;
        }

        // Экспоненциальная задержка: base * 2^retryCount
        long delaySeconds = BASE_DELAY_SECONDS * (long) Math.pow(2, event.getRetryCount() - 1);
        Instant nextAttempt = event.getUpdatedAt().plus(Duration.ofSeconds(delaySeconds));

        return Instant.now().isAfter(nextAttempt);
    }

    /**
     * Возвращает максимальное количество допустимых retry попыток.
     *
     * @return максимальный лимит повторных попыток
     */
    public int getMaxRetries() {
        return MAX_RETRIES;
    }
}