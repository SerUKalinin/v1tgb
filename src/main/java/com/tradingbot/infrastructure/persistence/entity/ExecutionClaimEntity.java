package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_claims",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_execution_claims_signal_id", columnNames = "signal_id"),
                @UniqueConstraint(name = "uq_execution_claims_execution_id", columnNames = "execution_id")
        }
)
@Getter@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class ExecutionClaimEntity {

    public static final String STATUS_CLAIMED = "CLAIMED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "signal_id", updatable = false, unique = true)
    private UUID signalId;

    @Column(name = "execution_id", updatable = false, unique = true)
    private UUID executionId;
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}