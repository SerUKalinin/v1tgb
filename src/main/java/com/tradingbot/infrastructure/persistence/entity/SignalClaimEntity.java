package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signal_claims")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class SignalClaimEntity {

    @Id
    @Column(name = "signal_id", nullable = false, updatable = false)
    private UUID signalId;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    @PrePersist
    protected void onCreate() {
        if (this.claimedAt == null) {
            this.claimedAt = Instant.now();
        }
    }
}
