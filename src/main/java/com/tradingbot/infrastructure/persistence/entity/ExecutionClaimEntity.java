package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

    public static final String STATUS_CLAIMED = "CLAIMED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(name = "signal_id", updatable = false, unique = true, nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID signalId;

    @Column(name = "execution_id", updatable = false, unique = true, nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID executionId;
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
