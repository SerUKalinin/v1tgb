package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_claims",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_execution_claims_signal_id", columnNames = "signal_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionClaimEntity {

    public static final String STATUS_CLAIMED = "CLAIMED";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "signal_id", nullable = false, updatable = false)
    private UUID signalId;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static class ExecutionClaimEntityBuilder {
        private UUID executionId;

        public ExecutionClaimEntityBuilder executionId(UUID executionId) {
            this.executionId = executionId;
            return this;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
