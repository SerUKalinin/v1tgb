package com.tradingbot.infrastructure.outbox;

import com.tradingbot.tracing.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Сервис публикации событий в Outbox.
 *
 * <p>Обеспечивает идемпотентное сохранение событий на основе {@link ExecutionContext}.
 * События сохраняются в таблице outbox для последующей асинхронной обработки
 * {@link OutboxProcessor}.
 *
 * <p>Идемпотентность гарантируется использованием {@code executionId} как уникального ключа.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Публикует событие в outbox.
     *
     * <p>Основные действия:
     * <ul>
     *     <li>Проверка на существование события с тем же executionId</li>
     *     <li>Вычисление следующего sequenceNumber для агрегата</li>
     *     <li>Сериализация payload в JSON</li>
     *     <li>Сохранение события в таблице outbox</li>
     * </ul>
     *
     * @param context       контекст исполнения события, содержащий executionId, signalId и другие SSOT-идентификаторы
     * @param aggregateType тип агрегата, к которому относится событие
     * @param eventType     тип события
     * @param payload       объект события для сериализации
     */
    @Transactional
    public void publishEvent(
            ExecutionContext context,
            String aggregateType,
            String eventType,
            Object payload
    ) {
        try {
            UUID executionId = context.attempt().executionId();

            /*
             * executionId = identity всего execution lifecycle.
             *
             * eventId = identity конкретного события внутри lifecycle.
             *
             * Один executionId может иметь несколько событий:
             * ORDER_CREATED, ORDER_ACCEPTED, ORDER_EXECUTED,
             * ORDER_REJECTED, ORDER_CANCELED и т.д.
             */
            UUID eventId =
                    IdentityFactory.deriveEventId(
                            executionId,
                            eventType
                    );

            /*
             * Идемпотентность должна работать по identity
             * конкретного Outbox-события, а не по execution lifecycle.
             */
            if (outboxRepository.existsById(eventId)) {
                log.info(
                        "[OUTBOX-SKIP] Event already exists. eventId={}, executionId={}, eventType={}",
                        eventId,
                        executionId,
                        eventType
                );
                return;
            }

            long nextSequence =
                    outboxRepository.getNextSequenceNumber(
                            context.aggregateId()
                    );

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(eventId)
                    .eventId(eventId)
                    .aggregateId(context.aggregateId())
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.NEW)
                    .sequenceNumber(nextSequence)
                    .createdAt(Instant.now())
                    .schemaVersion(1)
                    .signalId(context.signalId())
                    .orderId(
                            UUID.fromString(
                                    context.business().orderId()
                            )
                    )
                    .executionId(executionId)
                    .causationId(
                            context.attempt().causationId()
                    )
                    .correlationId(
                            context.identity().correlationId()
                    )
                    .build();

            outboxRepository.save(event);

            log.debug(
                    "[OUTBOX-PUBLISHED] eventId={}, executionId={}, eventType={}, aggregateId={}",
                    eventId,
                    executionId,
                    eventType,
                    context.aggregateId()
            );

        } catch (Exception e) {
            log.error(
                    "[OUTBOX-STRICT-ERROR] Context: {}, Event: {}",
                    context,
                    eventType,
                    e
            );

            throw new RuntimeException(
                    "Outbox publication failed due to identity/causality violation",
                    e
            );
        }
    }
}