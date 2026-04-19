package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * TECHNICAL ENTITY — Transactional Outbox.
 *
 * Гарантирует Reliable Messaging (at-least-once delivery).
 * Запись создаётся в одной транзакции с бизнес-операцией.
 * OutboxProcessor читает PENDING записи и публикует во внешние системы.
 *
 * Изменения относительно v1:
 * - eventId: String → UUID (нативный тип, экономия + индексируемость)
 * - payload: TEXT → JSONB (queryable, compressed, validated by Postgres)
 * - status: String → OutboxStatus enum (нет неконтролируемых значений)
 * - retry_count, max_retries, next_retry_at: добавлены для exponential backoff
 *   (без них processor зацикливается на broken event навсегда)
 * - error_message: добавлен для dead-letter queue диагностики
 */
@Entity
@Table(
        name = "outbox_events",
        indexes = {
                @Index(name = "idx_outbox_pending",         columnList = "next_retry_at"),
                @Index(name = "idx_outbox_client_order_id", columnList = "client_order_id"),
                @Index(name = "idx_outbox_failed",          columnList = "created_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    @Id
    @Column(name = "event_id", columnDefinition = "uuid", updatable = false, nullable = false)
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    /** Correlates outbox event с ордером для idempotency-проверок на downstream. */
    @Column(name = "client_order_id", nullable = false, length = 128)
    private String clientOrderId;

    /** Тип события — e.g. ORDER_APPROVED, POSITION_OPENED. */
    @Column(name = "type", nullable = false, length = 128)
    private String type;

    /**
     * Полезная нагрузка события в формате JSONB.
     * JSONB даёт: GIN-индексирование, валидацию структуры, operator-based поиск.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    // ── Retry / backoff ───────────────────────────────────────────────────────

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    /** Следующая допустимая попытка обработки. Индексируется для poll-запроса. */
    @Column(name = "next_retry_at", nullable = false)
    @Builder.Default
    private Instant nextRetryAt = Instant.now();

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Заполняется при первом изменении статуса из PENDING. */
    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}