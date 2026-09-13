package com.tradingbot.infrastructure.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Сущность для хранения уже обработанных outbox-событий.
 *
 * <p>Используется для обеспечения идемпотентности обработки событий:
 * один и тот же eventId не может быть обработан повторно одним consumer'ом.
 *
 * <p>Типичный сценарий использования:
 * <ul>
 *     <li>проверка через {@code existsById}</li>
 *     <li>фиксация факта обработки после успешного consume</li>
 * </ul>
 *
 * <p>Является частью механизма защиты от duplicate delivery в Outbox pipeline.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEventEntity {

    /**
     * Уникальный идентификатор события (eventId из outbox).
     */
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    /**
     * Время фактической обработки события.
     */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * Имя consumer-а, который обработал событие.
     */
    @Column(name = "consumer_name")
    private String consumerName;
}