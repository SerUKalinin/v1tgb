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
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Entity
@Table(name = "orders",
        indexes = {
                @Index(name = "idx_orders_client_order_id", columnList = "client_order_id"),
                @Index(name = "idx_orders_strategy_id", columnList = "strategy_id")
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class OrderEntity {    @Id
    private UUID id;

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

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 8)
    private BigDecimal price;

    @Column(name = "stop_loss", precision = 18, scale = 8)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 18, scale = 8)
    private BigDecimal takeProfit;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Long version;

    /**
     * Переводит ордер в статус FILLED (исполнен).
     */
    public void markAsFilled(String exchangeOrderId, BigDecimal executedQty) {
        validateTransition(com.tradingbot.common.enums.OrderStatus.FILLED.name());
        this.exchangeOrderId = exchangeOrderId;
        this.quantity = executedQty;
        this.status = com.tradingbot.common.enums.OrderStatus.FILLED.name();
        this.updatedAt = Instant.now();
        log.info("[ORDER-DOMAIN] Ордер {} переведен в статус FILLED. ExchangeID: {}, Qty: {}", this.id, exchangeOrderId, executedQty);
    }

    /**
     * Переводит ордер в статус PARTIALLY_FILLED.
     */
    public void markAsPartiallyFilled(String exchangeOrderId, BigDecimal executedQty) {
        validateTransition(com.tradingbot.common.enums.OrderStatus.PARTIALLY_FILLED.name());
        this.exchangeOrderId = exchangeOrderId;
        this.quantity = executedQty;
        this.status = com.tradingbot.common.enums.OrderStatus.PARTIALLY_FILLED.name();
        this.updatedAt = Instant.now();
        log.info("[ORDER-DOMAIN] Ордер {} частично исполнен. Qty: {}", this.id, executedQty);
    }

    /**
     * Переводит ордер в статус REJECTED (отклонен).
     */    public void markAsRejected(String reason) {
        validateTransition(com.tradingbot.common.enums.OrderStatus.REJECTED.name());
        this.status = com.tradingbot.common.enums.OrderStatus.REJECTED.name();
        this.updatedAt = Instant.now();
        log.warn("[ORDER-DOMAIN] Ордер {} отклонен. Причина: {}", this.id, reason);
    }

    /**
     * Принудительно переводит ордер в статус FILLED, игнорируя текущее состояние.
     * Используется только при рассинхронизации с биржей (Binance Source of Truth).
     */
    public void forceMarkAsFilled(String exchangeOrderId, BigDecimal executedQty) {
        this.exchangeOrderId = exchangeOrderId;
        this.quantity = executedQty;
        this.status = com.tradingbot.common.enums.OrderStatus.FILLED.name();
        this.updatedAt = Instant.now();
        log.warn("[ORDER-DOMAIN][DESYNC FIX] Ордер {} ПРИНУДИТЕЛЬНО переведен в FILLED. ExchangeID: {}", this.id, exchangeOrderId);
    }

    private void validateTransition(String newStatus) {
        String currentStatus = this.status;
        if (com.tradingbot.common.enums.OrderStatus.FILLED.name().equals(currentStatus) || 
            com.tradingbot.common.enums.OrderStatus.REJECTED.name().equals(currentStatus)) {
            throw new IllegalStateException(String.format("Невозможный переход из %s в %s для ордера %s", 
                    currentStatus, newStatus, this.id));
        }
    }
}