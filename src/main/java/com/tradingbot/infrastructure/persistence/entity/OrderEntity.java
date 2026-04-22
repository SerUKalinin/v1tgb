package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.common.enums.OrderSide;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders",
        indexes = {
                @Index(name = "idx_orders_client_order_id", columnList = "client_order_id"),
                @Index(name = "idx_orders_strategy_id", columnList = "strategy_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {
    @Id
    private String id;

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

    @Column(nullable = false)
    private BigDecimal quantity;

    private BigDecimal price;

    @Column(name = "stop_loss")
    private BigDecimal stopLoss;

    @Column(name = "take_profit")
    private BigDecimal takeProfit;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}