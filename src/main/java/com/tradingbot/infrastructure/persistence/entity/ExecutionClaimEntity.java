package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Сущность "Execution Claim".
 *
 * <p>Представляет собой факт захвата (claim) сигнала для исполнения конкретного executionId.
 * Гарантирует, что один сигнал может быть исполнен только одним executionId (идемпотентность).</p>
 *
 * <p>Используется в execution pipeline для предотвращения повторного исполнения одного и того же сигнала.</p>
 */
@Entity
@Table(name = "execution_claims",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_execution_claims_signal_id", columnNames = "signal_id"),
                @UniqueConstraint(name = "uq_execution_claims_execution_id", columnNames = "execution_id")
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionClaimEntity {

    /** Статус, обозначающий, что сигнал был захвачен */
    public static final String STATUS_CLAIMED = "CLAIMED";

    /** Уникальный идентификатор записи */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** Идентификатор сигнала (signalId), уникальный и неизменяемый */
    @Column(name = "signal_id", updatable = false, unique = true, nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID signalId;

    /** Идентификатор исполнения (executionId), уникальный и неизменяемый */
    @Column(name = "execution_id", updatable = false, unique = true, nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID executionId;

    /** Текущий статус захвата */
    @Column(name = "status", nullable = false)
    private String status;

    /** Время захвата сигнала */
    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    /** Время создания записи */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Фабричный метод для создания нового ExecutionClaimEntity.
     *
     * @param executionId идентификатор исполнения
     * @param signalId идентификатор сигнала
     * @return новая сущность ExecutionClaimEntity
     */
    public static ExecutionClaimEntity create(UUID executionId, UUID signalId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(signalId, "signalId must not be null");

        ExecutionClaimEntity entity = new ExecutionClaimEntity();
        entity.executionId = executionId;
        entity.signalId = signalId;
        entity.status = STATUS_CLAIMED;
        entity.claimedAt = Instant.now();
        return entity;
    }

    /**
     * Автоматическая установка createdAt перед сохранением.
     */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}