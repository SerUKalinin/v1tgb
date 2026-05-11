package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exception.InvalidOrderStateException;
import com.tradingbot.domain.exception.InvalidOrderTransitionException;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
public class Order {
    // Идентификационные данные
    private final UUID id;
    private final String clientOrderId;
    private final String symbol;
    private final String strategyId;
    private final UUID signalId;

    // Параметры ордера
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal originalQuantity;
    private final BigDecimal price;

    // Состояние жизненного цикла
    private OrderStatus status;
    private long version;

    // Данные исполнения
    private UUID executionId;
    private Instant executionStartedAt;
    private String exchangeOrderId;
    private BigDecimal executedQuantity;
    private BigDecimal averagePrice;
    private String rejectionReason;

    // Приватный конструктор для обеспечения целостности через фабричные методы
    private Order(UUID id,
                  String clientOrderId,
                  String symbol,
                  OrderSide side,
                  OrderType type,
                  BigDecimal originalQuantity,
                  BigDecimal price,
                  String strategyId,
                  UUID signalId,
                  OrderStatus status,
                  long version) {
        if (status == null) {
            throw new InvalidOrderStateException("Order status cannot be null");
        }
        this.id = id;
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.originalQuantity = originalQuantity;
        this.price = price;
        this.strategyId = strategyId;
        this.signalId = signalId != null ? signalId : UUID.randomUUID();
        this.status = status;
        this.version = version;
    }

    /**
     * Создает новый ордер в начальном состоянии PENDING_EXECUTION.
     */
    public static Order createPendingExecution(UUID id,
                                               String clientOrderId,
                                               String symbol,
                                               OrderSide side,
                                               OrderType type,
                                               BigDecimal originalQuantity,
                                               BigDecimal price,
                                               String strategyId,
                                               UUID signalId) {
        return new Order(id,
                clientOrderId,
                symbol,
                side,
                type,
                originalQuantity,
                price,
                strategyId,
                signalId,
                OrderStatus.PENDING_EXECUTION,
                0L);
    }

    /**
     * Восстанавливает состояние агрегата из хранилища (Persistence -> Domain).
     */
    public static Order reconstruct(UUID id,
                                    String clientOrderId,
                                    String symbol,
                                    OrderSide side,
                                    OrderType type,
                                    BigDecimal originalQuantity,
                                    BigDecimal price,
                                    String strategyId,
                                    UUID signalId,
                                    OrderStatus status,
                                    long version,
                                    UUID executionId,
                                    Instant executionStartedAt,
                                    String exchangeOrderId,
                                    BigDecimal executedQuantity,
                                    BigDecimal averagePrice,
                                    String rejectionReason) {
        Order order = new Order(id,
                clientOrderId,
                symbol,
                side,
                type,
                originalQuantity,
                price,
                strategyId,
                signalId,
                status,
                version);
        order.executionId = executionId;
        order.executionStartedAt = executionStartedAt;
        order.exchangeOrderId = exchangeOrderId;
        order.executedQuantity = executedQuantity;
        order.averagePrice = averagePrice;
        order.rejectionReason = rejectionReason;
        return order;
    }

    // --- Бизнес-логика и переходы состояний ---

    public void assignExecutionOwner(UUID executionId) {
        if (executionId == null) {
            throw new IllegalArgumentException("executionId cannot be null");
        }
        if (this.executionId != null && !this.executionId.equals(executionId)) {
            throw new IllegalStateException(String.format("Order %s already claimed by %s", this.id, this.executionId));
        }
        this.executionId = executionId;
    }

    public void clearExecutionOwner() {
        this.executionId = null;
    }

    public void setExecutionStartedAt(Instant executionStartedAt) {
        this.executionStartedAt = executionStartedAt;
    }

    public void markExecuting() {
        validateTransitionOrThrow(OrderStatus.EXECUTING);
        this.status = OrderStatus.EXECUTING;
    }

    public void fill(String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) {
        validateTransitionOrThrow(OrderStatus.FILLED);
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    public void applyPartialFill(BigDecimal qty, BigDecimal price) {
        if (OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new InvalidOrderTransitionException("Cannot partial fill terminal order");
        }
        this.executedQuantity = qty;
        this.averagePrice = price;
        this.status = OrderStatus.PARTIALLY_FILLED;
    }

    public void markAsRejected(String reason) {
        validateTransitionOrThrow(OrderStatus.REJECTED);
        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public void markCancelled() {
        validateTransitionOrThrow(OrderStatus.CANCELED);
        this.status = OrderStatus.CANCELED;
    }

    public void markAsUnknown() {
        validateTransitionOrThrow(OrderStatus.UNKNOWN);
        this.status = OrderStatus.UNKNOWN;
    }

    // --- Вспомогательные методы ---

    public BigDecimal getRemainingQuantity() {
        BigDecimal executed = executedQuantity == null ? BigDecimal.ZERO : executedQuantity;
        return originalQuantity.subtract(executed);
    }

    public BigDecimal getQuantity() {
        return originalQuantity;
    }

    private void validateTransitionOrThrow(OrderStatus targetStatus) {
        if (this.status == targetStatus) {
            return;
        }

        if (OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new IllegalStateException(String.format("Cannot transition from terminal %s to %s", this.status, targetStatus));
        }

        if (!OrderStateTransitionPolicy.canTransition(this.status, targetStatus)) {
            throw new IllegalStateException(String.format("Transition from %s to %s forbidden by policy", this.status, targetStatus));
        }
    }
}
