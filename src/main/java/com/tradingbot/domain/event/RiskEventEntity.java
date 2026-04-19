package com.tradingbot.domain.event;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * EVENT STORE — Результат проверки риск-менеджмента (append-only).
 *
 * Фиксирует факты одобрения или нарушения лимитов.
 * НИКОГДА не обновляется после записи — только INSERT.
 *
 * Изменения относительно v1:
 * - aggregateId: String → Long (соответствует orders.id после миграции PK)
 * - payload: String + @JdbcTypeCode(JSON) → явный columnDefinition = "jsonb"
 * - createdAt: OffsetDateTime → Instant (консистентность с остальными entity)
 * - @Version: УДАЛЁН — event store append-only, версионирование через поле version
 */
@Entity
@Table(
        name = "risk_events",
        indexes = {
                @Index(name = "idx_risk_events_aggregate", columnList = "aggregate_id, version"),
                @Index(name = "idx_risk_events_type",      columnList = "event_type, created_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_risk_events_event_id",          columnNames = {"event_id"}),
                @UniqueConstraint(name = "uq_risk_events_aggregate_version", columnNames = {"aggregate_id", "version"})
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Глобально уникальный ID события — UUID для идемпотентности.
     * Генерируется на стороне приложения перед persist.
     */
    @Column(name = "event_id", unique = true, nullable = false, columnDefinition = "uuid")
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    /**
     * ID ордера (aggregate root).
     * Long — соответствует orders.id после миграции String→Long.
     * Soft reference (не FK): event store не должен иметь hard FK на агрегат —
     * это нарушает изоляцию event log и усложняет прунинг.
     */
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /** Монотонная версия в рамках агрегата. Уникальна в паре (aggregate_id, version). */
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    /**
     * Полезная нагрузка события.
     * JSONB — queryable: можно добавить GIN index по конкретным полям payload.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID();
        }
    }
}