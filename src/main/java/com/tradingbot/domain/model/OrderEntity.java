package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * AGGREGATE ROOT — Торговый ордер.
 *
 * Жизненный цикл: NEW → ACCEPTED → APPROVED → PENDING_EXECUTION → EXECUTING → FILLED|REJECTED|ERROR
 * Связан с: User (владелец), Trade (дочерние исполнения).
 *
 * Изменения относительно v1:
 * - id: String → Long IDENTITY (устранён random-insert антипаттерн)
 * - user: добавлен ManyToOne FK → users.id
 * - status/side/type: EnumType.STRING (Postgres ENUM через column definition)
 * - version: @Version оставлен — оптимистичная блокировка критична для FSM
 * - trades: НЕ маппим @OneToMany — избегаем N+1 и LazyInitializationException
 */
@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_status",          columnList = "status"),
                @Index(name = "idx_orders_user_id",         columnList = "user_id"),
                @Index(name = "idx_orders_strategy_status", columnList = "strategy_id, status"),
                @Index(name = "idx_orders_exchange_id",     columnList = "exchange_order_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Idempotency / external references ────────────────────────────────────

    /** Клиентский идемпотентный ключ. Генерируется на стороне приложения. */
    @Column(name = "client_order_id", nullable = false, unique = true, length = 128)
    private String clientOrderId;

    /** ID ордера на бирже. Заполняется после успешного исполнения. */
    @Column(name = "exchange_order_id", length = 128)
    private String exchangeOrderId;

    // ── Ownership ─────────────────────────────────────────────────────────────

    /**
     * Владелец ордера.
     * LAZY — обязательно: загружать User при каждом обращении к ордеру недопустимо.
     * insertable/updatable = false + отдельный user_id — для JPQL-запросов по id без join.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_orders_user"))
    private User user;

    /** Денормализованный FK для запросов без join (watchdog, analytics). */
    @Column(name = "user_id", insertable = false, updatable = false, nullable = false)
    private Long userId;

    @Column(name = "strategy_id", nullable = false, length = 128)
    private String strategyId;

    // ── Instrument ────────────────────────────────────────────────────────────

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private OrderType type;

    // ── Amounts ───────────────────────────────────────────────────────────────
    // NUMERIC(28,8) — задаём явно. Без precision Hibernate fallback → float8, что неприемлемо для денег.

    @Column(name = "quantity", nullable = false, precision = 28, scale = 8)
    private BigDecimal quantity;

    @Column(name = "price", nullable = false, precision = 28, scale = 8)
    private BigDecimal price;

    @Column(name = "stop_loss", precision = 28, scale = 8)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 28, scale = 8)
    private BigDecimal takeProfit;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private OrderStatus status = OrderStatus.NEW;

    // ── Distributed execution fencing ────────────────────────────────────────

    /** ID воркера, захватившего ордер для исполнения. */
    @Column(name = "execution_owner", length = 255)
    private String executionOwner;

    /** Истечение lease — если прошло, ордер доступен для повторного захвата. */
    @Column(name = "execution_expires_at")
    private Instant executionExpiresAt;

    // ── Versioning ────────────────────────────────────────────────────────────

    /**
     * Оптимистичная блокировка — JPA @Version.
     * Защищает от concurrent FSM transitions (race condition между двумя воркерами).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Версия риск-стейта на момент апрува.
     * Используется для детектирования stale-апрувов при replay.
     */
    @Column(name = "risk_state_version", nullable = false)
    @Builder.Default
    private Long riskStateVersion = 0L;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}