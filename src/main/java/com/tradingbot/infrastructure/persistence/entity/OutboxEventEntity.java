package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.infrastructure.outbox.OutboxStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEventEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "lock_owner")
    private String lockOwner;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @PrePersist
    protected void onCreate() {
        if (status == null) status = OutboxStatus.NEW;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
