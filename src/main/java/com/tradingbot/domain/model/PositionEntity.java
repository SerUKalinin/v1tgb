package com.tradingbot.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AGGREGATE ROOT — Рыночная позиция.
 *
 * Агрегирует накопленный объём и среднюю цену входа по (symbol, strategyId, userId).
 * Обновляется на основе исполненных TradeEntity.
 *
 * Изменения относительно v1:
 * - id: String symbol → Long IDENTITY (symbol как PK = невозможна история + нет мультипользовательности)
 * - user: добавлен ManyToOne FK — позиция без владельца нарушает изоляцию данных
 * - уникальность: UNIQUE(symbol, strategy_id, user_id) — правильный бизнес-ключ
 * - status: String → PositionStatus enum
 * - PositionId.java (@EmbeddedId) — УДАЛЁН, заменён суррогатным PK + уникальным индексом
 * - lastTradeId: Long (loose reference) — сохраняем как есть; hard FK на trade создаёт
 *   circular dependency Order→Trade→Position→Trade, что опасно при каскадных операциях
 */
@Entity
@Table(
        name = "positions",
        indexes = {
                @Index(name = "idx_positions_user_strategy", columnList = "user_id, strategy_id"),
                @Index(name = "idx_positions_symbol_status", columnList = "symbol, status"),
                @Index(name = "idx_positions_close_req",     columnList = "close_request_id")
        },
        uniqueConstraints = {
                // ONE active position per (symbol, strategy, user)
                @UniqueConstraint(
                        name = "uq_positions_open",
                        columnNames = {"symbol", "strategy_id", "user_id"}
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Business identity (уникальный ключ бизнес-смысла) ────────────────────

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Column(name = "strategy_id", nullable = false, length = 128)
    private String strategyId;

    // ── Ownership ─────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_positions_user"))
    private User user;

    @Column(name = "user_id", insertable = false, updatable = false, nullable = false)
    private Long userId;

    // ── Position state ────────────────────────────────────────────────────────

    @Column(name = "net_quantity", nullable = false, precision = 28, scale = 8)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "avg_entry_price", precision = 28, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "realized_pnl", nullable = false, precision = 28, scale = 8)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "stop_loss", precision = 28, scale = 8)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 28, scale = 8)
    private BigDecimal takeProfit;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PositionStatus status = PositionStatus.OPEN;

    /**
     * ID последнего trade, обновившего позицию.
     * Loose reference (не FK) — избегаем circular dependency с TradeEntity.
     */
    @Column(name = "last_trade_id")
    private Long lastTradeId;

    /**
     * UUID для идемпотентного запроса на закрытие позиции.
     * Гарантирует at-most-once semantics для PositionClosingService.
     */
    @Column(name = "close_request_id")
    private UUID closeRequestId;

    // ── Optimistic lock ───────────────────────────────────────────────────────

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}