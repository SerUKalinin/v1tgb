package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "risk_state_snapshots")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskStateSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal balance;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal equity;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal dailyPnl;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal maxEquity;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String processedEventIds;
}
