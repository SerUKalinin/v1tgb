package com.tradingbot.domain.model;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exception.InvalidOrderStateException;
import com.tradingbot.domain.exception.InvalidOrderTransitionException;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
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
    private Instant createdAt;
    private Instant updatedAt;

    // Данные исполнения
    private UUID executionId;
    private Instant executionStartedAt;
    private int executionAttempts = 0;
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
        this.id = Objects.requireNonNull(id, "orderId is required");
        this.clientOrderId = Objects.requireNonNull(clientOrderId, "clientOrderId is required");
        this.symbol = Objects.requireNonNull(symbol, "symbol is required");
        this.side = Objects.requireNonNull(side, "side is required");
        this.type = Objects.requireNonNull(type, "type is required");
        this.originalQuantity = Objects.requireNonNull(originalQuantity, "quantity is required");
        this.price = price;
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId is required");
        this.signalId = Objects.requireNonNull(signalId, "signalId is required");

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
                                    Instant createdAt,
                                    Instant updatedAt,
                                    UUID executionId,
                                    Instant executionStartedAt,
                                    String exchangeOrderId,
                                    BigDecimal executedQuantity,
                                    BigDecimal averagePrice,
                                    String rejectionReason,
                                    int executionAttempts) {
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
        order.createdAt = createdAt;
        order.updatedAt = updatedAt;
        order.executionId = executionId;
        order.executionStartedAt = executionStartedAt;
        order.exchangeOrderId = exchangeOrderId;
        order.executedQuantity = executedQuantity;
        order.averagePrice = averagePrice;
        order.rejectionReason = rejectionReason;
        order.executionAttempts = executionAttempts;
        return order;
    }
    // --- Бизнес-логика и переходы состояний ---

    public UUID getExecutionId() {
        return executionId;
    }

    public void assignExecutionOwner(ExecutionContext context) {
        UUID executionId = context.attempt().executionId();
        if (this.executionId != null && !this.executionId.equals(executionId)) {
            throw new IllegalStateException(String.format("Order %s already claimed by %s", this.id, this.executionId));
        }
        this.executionId = executionId;
        this.executionStartedAt = Instant.now();
        this.executionAttempts++;
    }

    public void clearExecutionOwner(ExecutionContext context) {
        this.executionId = null;
    }

    public void markExecuting(ExecutionContext context) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.EXECUTING);
        this.status = OrderStatus.EXECUTING;
    }

    public void fill(ExecutionContext context, String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.FILLED);
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    public void fill(ExecutionContext context, BigDecimal executedQty, BigDecimal executedPrice) {
        fill(context, this.exchangeOrderId, executedQty, executedPrice);
    }

    public void applyPartialFill(ExecutionContext context, BigDecimal qty, BigDecimal price) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.PARTIALLY_FILLED);
        this.executedQuantity = qty;
        this.averagePrice = price;
        this.status = OrderStatus.PARTIALLY_FILLED;
    }
    public void markRecovering(ExecutionContext context) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.RECOVERING);
        this.status = OrderStatus.RECOVERING;
    }

    public void markAsRejected(ExecutionContext context, String reason) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.REJECTED);
        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public void markCancelled(ExecutionContext context) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.CANCELED);
        this.status = OrderStatus.CANCELED;
    }

    public void markAsUnknown(ExecutionContext context) {
        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.UNKNOWN);
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
