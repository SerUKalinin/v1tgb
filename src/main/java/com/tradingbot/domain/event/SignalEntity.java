package com.tradingbot.domain.event;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * EVENT SOURCE / READ MODEL — Входящий торговый сигнал от стратегии.
 *
 * Первичный триггер для создания OrderEntity.
 * Хранится для аудита и replay.
 *
 * Изменения относительно v1:
 * - user: добавлен nullable ManyToOne (системные сигналы без владельца допустимы)
 * - timestamp vs createdAt: оба сохранены — timestamp = биржевое время сигнала,
 *   createdAt = время записи в БД (разные семантики)
 * - precision: явный NUMERIC(28,8) для price/sl/tp полей
 */
@Entity
@Table(
        name = "signals",
        indexes = {
                @Index(name = "idx_signals_strategy_created", columnList = "strategy_id, created_at"),
                @Index(name = "idx_signals_symbol_created",   columnList = "symbol, created_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private SignalType type;

    @Column(name = "strategy_id", nullable = false, length = 128)
    private String strategyId;

    // Nullable: системные сигналы могут не иметь владельца
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_signals_user"))
    private User user;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    // ── Price levels ──────────────────────────────────────────────────────────

    @Column(name = "price", precision = 28, scale = 8)
    private BigDecimal price;

    @Column(name = "take_profit1", precision = 28, scale = 8)
    private BigDecimal takeProfit1;

    @Column(name = "take_profit2", precision = 28, scale = 8)
    private BigDecimal takeProfit2;

    @Column(name = "stop_loss", precision = 28, scale = 8)
    private BigDecimal stopLoss;

    // ── Timestamps ────────────────────────────────────────────────────────────

    /** Время записи сигнала в БД. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Оригинальное время генерации сигнала на стороне стратегии/биржи. */
    @Column(name = "timestamp")
    private Instant timestamp;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}