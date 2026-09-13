package com.tradingbot.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Сервис идемпотентности обработки событий.
 *
 * <p>Гарантирует, что каждое outbox-событие будет обработано
 * строго один раз на уровне consumer'ов.</p>
 *
 * <p>Используется для защиты от повторной обработки при:
 * <ul>
 *   <li>retry механизмах</li>
 *   <li>конкурентной обработке</li>
 *   <li>повторной доставке событий</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository repository;

    /**
     * Проверяет, было ли событие уже обработано.
     *
     * <p>Метод требует активную транзакцию (MANDATORY).</p>
     *
     * @param eventId идентификатор события
     * @return true если событие уже обработано
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isAlreadyProcessed(UUID eventId) {
        return repository.existsById(eventId);
    }

    /**
     * Помечает событие как обработанное данным consumer'ом.
     *
     * <p>Сохраняет факт обработки с временной меткой и именем consumer'а.
     * В случае конкурентной записи возможен DataIntegrityViolationException,
     * который безопасно игнорируется.</p>
     *
     * @param eventId идентификатор события
     * @param consumerName имя обработчика
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsProcessed(UUID eventId, String consumerName) {
        if (eventId == null) {
            log.warn("[IDEMPOTENCY] Attempted to mark null eventId as processed");
            return;
        }
        try {
            ProcessedEventEntity entity = new ProcessedEventEntity();
            entity.setEventId(eventId);
            entity.setProcessedAt(Instant.now());
            entity.setConsumerName(consumerName);

            repository.saveAndFlush(entity);
            log.debug("[IDEMPOTENCY] Event {} marked as processed by {}", eventId, consumerName);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("[IDEMPOTENCY] Event already processed eventId={}", eventId);
        }
    }
}