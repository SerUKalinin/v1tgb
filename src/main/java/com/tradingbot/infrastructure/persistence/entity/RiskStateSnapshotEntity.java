package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.domain.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TECHNICAL ENTITY — Периодический снапшот P&L / equity для risk engine recovery.
 *
 * Используется RiskStateRecoveryService при рестарте для восстановления
 * in-memory состояния риск-движка без replay всей истории событий.
 *
 * Изменения относительно v1:
 * - processedEventIds: String TEXT → JSONB array (структурно типизировано,
 *   без ручной сериализации/парсинга в Java)
 * - strategyId: добавлен — без него невозможен recovery при нескольких стратегиях
 * - user: добавлен nullable FK — для per-user risk state isolation
 * - precision: явный NUMERIC(28,8) для всех финансовых полей
 */
@Entity
@Table(
        name = "risk_state_snapshots",
        indexes = {
                @Index(name = "idx_risk_state_snap_timestamp", columnList = "timestamp"),
                @Index(name = "idx_risk_state_snap_strategy",  columnList = "strategy_id, timestamp")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskStateSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    // ── Equity state ──────────────────────────────────────────────────────────

    @Column(name = "balance", nullable = false, precision = 28, scale = 8)
    private BigDecimal balance;

    @Column(name = "equity", nullable = false, precision = 28, scale = 8)
    private BigDecimal equity;

    @Column(name = "daily_pnl", nullable = false, precision = 28, scale = 8)
    private BigDecimal dailyPnl;

    @Column(name = "max_equity", nullable = false, precision = 28, scale = 8)
    private BigDecimal maxEquity;

    /**
     * IDs событий, уже включённых в этот снапшот.
     * JSONB array: ["uuid1", "uuid2", ...] — не нужна ручная сериализация в Java.
     * Позволяет делать Postgres-side: processedEventIds @> '["uuid1"]'::jsonb
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "processed_event_ids", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private String processedEventIds = "[]";

    // ── Ownership ─────────────────────────────────────────────────────────────

    @Column(name = "strategy_id", length = 128)
    private String strategyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_risk_state_snap_user"))
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