package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exception.InvalidOrderTransitionException;
import com.tradingbot.domain.exception.InvalidOrderTransitionException;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Order {
    private final UUID id;
    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal originalQuantity;
    private final BigDecimal price;
    private final String strategyId;
    @Builder.Default
    private final String signalId = UUID.randomUUID().toString();
    private UUID executionId;
    @Builder.Default
    private long version = 0L;

    private OrderStatus status;
    private Instant executionStartedAt;

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

    private String exchangeOrderId;
    private BigDecimal executedQuantity;
    private BigDecimal averagePrice;
    private String rejectionReason;

    public void fill(String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) {
        validateTransitionOrThrow(OrderStatus.FILLED);
        if (this.status == OrderStatus.FILLED) {
            return;
        }
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    public void markAsRejected(String reason) {
        validateTransitionOrThrow(OrderStatus.REJECTED);
        if (this.status == OrderStatus.REJECTED) {
            return;
        }
        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public void markAsUnknown() {
        validateTransitionOrThrow(OrderStatus.UNKNOWN);
        if (this.status == OrderStatus.UNKNOWN) {
            return;
        }
        this.status = OrderStatus.UNKNOWN;
    }

    public void markExecuting() {
        validateTransitionOrThrow(OrderStatus.EXECUTING);
        if (this.status == OrderStatus.EXECUTING) {
            return;
        }
        this.status = OrderStatus.EXECUTING;
    }

    public void markCancelled() {
        validateTransitionOrThrow(OrderStatus.CANCELED);
        if (this.status == OrderStatus.CANCELED) {
            return;
        }
        this.status = OrderStatus.CANCELED;
    }

    public BigDecimal getRemainingQuantity() {
        return originalQuantity.subtract(executedQuantity == null ? BigDecimal.ZERO : executedQuantity);
    }

    public void applyPartialFill(BigDecimal qty, BigDecimal price) {
        if (OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new InvalidOrderTransitionException("Cannot partial fill terminal order");
        }
        if (this.status == OrderStatus.PARTIALLY_FILLED) {
            return;
        }
        this.executedQuantity = qty;
        this.averagePrice = price;
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
