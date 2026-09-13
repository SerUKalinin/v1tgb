package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA-сущность снимка капитала (equity snapshot).
 *
 * <p>Используется для хранения исторических значений состояния капитала системы:
 * общего капитала, доступного баланса и нереализованного PnL.</p>
 *
 * <p>Применяется для аналитики, мониторинга и восстановления состояния системы.</p>
 */
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

    /** Уникальный идентификатор записи */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Общий капитал (total equity) */
    @Column(name = "total_equity", nullable = false)
    private BigDecimal equity;

    /** Доступный баланс (available balance) */
    @Column(name = "available_balance", nullable = false)
    private BigDecimal balance;

    /** Нереализованный PnL */
    @Column(name = "unrealized_pnl", nullable = false)
    private BigDecimal unrealizedPnl;

    /** Идентификатор стратегии */
    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    /** Временная метка снимка */
    @Column(nullable = false)
    private Instant timestamp;
}