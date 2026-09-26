package com.tradingbot.infrastructure.outbox;

import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Сервис идемпотентности обработки событий.
 *
 * <p>
 * Базовый режим использует eventId напрямую.
 *
 * <p>
 * Для нескольких независимых consumers одного и того же outbox event
 * используется consumer-scoped ключ:
 *
 * <pre>
 * consumerKey =
 *     IdentityFactory.deriveEventId(
 *         eventId,
 *         consumerName
 *     )
 * </pre>
 *
 * Это позволяет одному TRADE_CREATED быть обработанным:
 *
 * PositionProjectionHandler
 * +
 * EquityProjectionHandler
 *
 * независимо друг от друга.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository repository;

    /**
     * Проверяет, было ли событие уже обработано
     * в legacy/global режиме.
     *
     * <p>
     * Существующие consumers используют этот режим.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isAlreadyProcessed(
            UUID eventId
    ) {
        return repository.existsById(eventId);
    }

    /**
     * Проверяет, было ли событие обработано
     * конкретным consumer.
     *
     * <p>
     * Idempotency key зависит одновременно от:
     *
     * <ul>
     *     <li>eventId</li>
     *     <li>consumerName</li>
     * </ul>
     *
     * Поэтому один и тот же outbox event может быть независимо
     * обработан несколькими consumers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isAlreadyProcessedByConsumer(
            UUID eventId,
            String consumerName
    ) {
        UUID consumerScopedKey =
                consumerScopedKey(
                        eventId,
                        consumerName
                );

        return repository.existsById(
                consumerScopedKey
        );
    }

    /**
     * Помечает событие как обработанное
     * в legacy/global режиме.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsProcessed(
            UUID eventId,
            String consumerName
    ) {
        saveProcessedMarker(
                eventId,
                consumerName
        );
    }

    /**
     * Помечает событие обработанным
     * конкретным consumer.
     *
     * <p>
     * Один и тот же eventId может иметь отдельный marker
     * для каждого consumer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAsProcessedByConsumer(
            UUID eventId,
            String consumerName
    ) {
        UUID consumerScopedKey =
                consumerScopedKey(
                        eventId,
                        consumerName
                );

        saveProcessedMarker(
                consumerScopedKey,
                consumerName
        );
    }

    private void saveProcessedMarker(
            UUID storageKey,
            String consumerName
    ) {
        if (storageKey == null) {
            log.warn(
                    "[IDEMPOTENCY] Attempted to mark null storage key as processed"
            );
            return;
        }

        try {
            ProcessedEventEntity entity =
                    new ProcessedEventEntity();

            entity.setEventId(
                    storageKey
            );

            entity.setProcessedAt(
                    Instant.now()
            );

            entity.setConsumerName(
                    consumerName
            );

            repository.saveAndFlush(
                    entity
            );

            log.debug(
                    "[IDEMPOTENCY] Event {} marked as processed by {}",
                    storageKey,
                    consumerName
            );

        } catch (DataIntegrityViolationException e) {

            log.warn(
                    "[IDEMPOTENCY] Event already processed. key={}, consumer={}",
                    storageKey,
                    consumerName
            );
        }
    }

    private UUID consumerScopedKey(
            UUID eventId,
            String consumerName
    ) {
        if (eventId == null) {
            throw new IllegalArgumentException(
                    "eventId cannot be null"
            );
        }

        if (consumerName == null
                || consumerName.isBlank()) {

            throw new IllegalArgumentException(
                    "consumerName cannot be null or blank"
            );
        }

        return IdentityFactory.deriveEventId(
                eventId,
                "consumer:" + consumerName
        );
    }
}