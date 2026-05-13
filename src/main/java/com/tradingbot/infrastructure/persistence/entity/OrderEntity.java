package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Entity
@Table(name = "orders",
        indexes = {
                @Index(name = "idx_orders_client_order_id", columnList = "client_order_id"),
                @Index(name = "idx_orders_strategy_id", columnList = "strategy_id"),
                @Index(name = "idx_orders_signal_id", columnList = "signal_id", unique = true)
        }
)@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class OrderEntity {
    @Id    private UUID id;

    @Column(name = "client_order_id", nullable = false, unique = true, updatable = false)
    private String clientOrderId;

    @Column(name = "exchange_order_id")
    private String exchangeOrderId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderType type;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal quantity;

    @Column(precision = 38, scale = 18)
    private BigDecimal price;

    @Column(name = "stop_loss", precision = 38, scale = 18)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 38, scale = 18)
    private BigDecimal takeProfit;

    @Column(name = "executed_quantity", precision = 38, scale = 18)
    private BigDecimal executedQuantity;

    @Column(name = "average_price", precision = 38, scale = 18)
    private BigDecimal averagePrice;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "signal_id", nullable = false, updatable = false)
    private UUID signalId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "execution_started_at")
    private Instant executionStartedAt;

    @Column(name = "execution_attempts")
    private int executionAttempts;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Enumerated(EnumType.STRING)    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}