package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private OrderStatus status;
    private String exchangeOrderId;
    private BigDecimal executedQuantity;
    private BigDecimal averagePrice;
    private String rejectionReason;

    public void fill(String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) {
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    public void markAsRejected(String reason) {
        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public void markExecuting() {
        this.status = OrderStatus.EXECUTING;
    }

    public BigDecimal getRemainingQuantity() {
        return originalQuantity.subtract(executedQuantity == null ? BigDecimal.ZERO : executedQuantity);
    }

    public void applyPartialFill(BigDecimal qty, BigDecimal price) {
        this.executedQuantity = qty;
        this.averagePrice = price;
    }

    public BigDecimal getQuantity() {
        return originalQuantity;
    }}
