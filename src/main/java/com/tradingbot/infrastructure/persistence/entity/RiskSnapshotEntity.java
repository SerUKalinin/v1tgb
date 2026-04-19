package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * TECHNICAL ENTITY — Снапшот состояния риск-движка.
 *
 * ПАТЧ: aggregateId добавлен для обратной совместимости с
 * RiskSnapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc()
 *
 * TODO: после рефакторинга репозитория убрать aggregateId.
 */
@Entity
@Table(
        name = "risk_snapshots",
        indexes = {
                @Index(name = "idx_risk_snapshots_timestamp",    columnList = "timestamp"),
                @Index(name = "idx_risk_snapshots_aggregate_id", columnList = "aggregate_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false, unique = true, length = 128)
    private String snapshotId;

    /** Legacy: нужен для RiskSnapshotRepository до рефакторинга. */
    @Column(name = "aggregate_id", length = 128)
    private String aggregateId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_json", columnDefinition = "jsonb", nullable = false)
    private String stateJson;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    /** Alias для JPQL сортировки в репозитории: ORDER BY lastVersion DESC */
    public Long getLastVersion() {
        return this.version;
    }

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }
}