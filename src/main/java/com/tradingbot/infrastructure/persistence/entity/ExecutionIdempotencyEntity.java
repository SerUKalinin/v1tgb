package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * TECHNICAL ENTITY — Идемпотентность исполнения ордеров на бирже.
 *
 * PK = client_order_id — естественный идемпотентный ключ.
 * Создаётся перед отправкой на биржу, обновляется после получения ответа.
 *
 * Изменения относительно v1:
 * - status: String → ExecutionIdempotencyStatus enum (нет неконтролируемых значений)
 * - updated_at: убран @PreUpdate дубль (теперь через DDL trigger set_updated_at)
 * - exchangeOrderId индекс: добавлен для reconciliation queries
 */
@Entity
@Table(
        name = "execution_idempotency",
        indexes = {
                @Index(name = "idx_exec_idemp_client_order_id", columnList = "client_order_id", unique = true),
                @Index(name = "idx_exec_idemp_exchange_id",     columnList = "exchange_order_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionIdempotencyEntity {

    @Id
    @Column(name = "client_order_id", length = 128)
    private String clientOrderId;

    @Column(name = "exchange_order_id", length = 128)
    private String exchangeOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExecutionIdempotencyStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}