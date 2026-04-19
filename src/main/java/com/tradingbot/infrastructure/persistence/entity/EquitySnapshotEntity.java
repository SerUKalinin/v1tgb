package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.domain.model.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TECHNICAL ENTITY — Снапшот equity curve для аналитики и дашборда.
 *
 * Append-only. Используется AnalyticsService и SaasMonitoringController.
 *
 * Изменения относительно v1:
 * - user: добавлен nullable FK — изоляция equity curve per user
 * - precision: явный NUMERIC(28,8) для всех BigDecimal полей
 * - индексы: добавлен составной (strategy_id, timestamp) для временных рядов
 */
@Entity
@Table(
        name = "equity_snapshots",
        indexes = {
                @Index(name = "idx_equity_snap_strategy_time", columnList = "strategy_id, timestamp"),
                @Index(name = "idx_equity_snap_user_time",     columnList = "user_id, timestamp")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EquitySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "balance", nullable = false, precision = 28, scale = 8)
    private BigDecimal balance;

    @Column(name = "equity", nullable = false, precision = 28, scale = 8)
    private BigDecimal equity;

    @Column(name = "unrealized_pnl", nullable = false, precision = 28, scale = 8)
    private BigDecimal unrealizedPnl;

    @Column(name = "strategy_id", nullable = false, length = 128)
    private String strategyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_equity_snap_user"))
    private User user;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }
}