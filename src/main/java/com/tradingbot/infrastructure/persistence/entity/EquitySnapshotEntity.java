package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "equity_snapshots",
        indexes = @Index(name = "idx_equity_snapshots_timestamp", columnList = "timestamp")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EquitySnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "total_equity", nullable = false)
    private BigDecimal equity;

    @Column(name = "available_balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "unrealized_pnl", nullable = false)
    private BigDecimal unrealizedPnl;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(nullable = false)
    private Instant timestamp;}
