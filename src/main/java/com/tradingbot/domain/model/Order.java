package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import java.math.RoundingMode;

/**
 * Aggregate Root для управления жизненным циклом ордера.
 */
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
    private final BigDecimal price; // Limit price
    private final String strategyId;
    
    private OrderStatus status;
    private String exchangeOrderId;
    private BigDecimal executedQuantity;
    private BigDecimal averagePrice;

    /**
     * Переводит ордер в статус PARTIALLY_FILLED или FILLED на основе дельты исполнения.
     * Реализует корректный пересчет средневзвешенной цены (VWAP).
     */
    public void markAsPartiallyFilled(BigDecimal executedQtyDelta, BigDecimal executionPrice) {
        if (executedQtyDelta == null || executedQtyDelta.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        validateTransition(OrderStatus.PARTIALLY_FILLED);
        
        BigDecimal currentExecutedQty = executedQuantity != null ? executedQuantity : BigDecimal.ZERO;
        BigDecimal currentAvgPrice = averagePrice != null ? averagePrice : BigDecimal.ZERO;
        
        BigDecimal newExecutedQuantity = currentExecutedQty.add(executedQtyDelta);
        
        // Инвариант: исполненный объем не может превышать исходный
        if (newExecutedQuantity.compareTo(originalQuantity) > 0) {
            throw new IllegalStateException(String.format(
                "Исполненный объем (%s) превышает исходный (%s) для ордера %s", 
                newExecutedQuantity, originalQuantity, this.id));
        }

        // Пересчет средневзвешенной цены: (P1*Q1 + P2*Q2) / (Q1 + Q2)
        BigDecimal totalCost = currentAvgPrice.multiply(currentExecutedQty)
                .add(executionPrice.multiply(executedQtyDelta));
        
        this.averagePrice = totalCost.divide(newExecutedQuantity, 18, RoundingMode.HALF_UP);
        this.executedQuantity = newExecutedQuantity;
        
        // Инвариант: если объем заполнен полностью -> FILLED
        if (this.executedQuantity.compareTo(originalQuantity) == 0) {
            this.status = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }
    /**
     * Переводит ордер в статус FILLED (полное исполнение одним событием).
     */
    public void markAsFilled(String exchangeOrderId, BigDecimal executedQty, BigDecimal executionPrice) {
        validateTransition(OrderStatus.FILLED);
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executionPrice != null ? executionPrice : (this.price != null ? this.price : BigDecimal.ZERO);
        this.status = OrderStatus.FILLED;
    }
    /**
     * Расчет остатка (инвариант).
     */
    public BigDecimal getRemainingQuantity() {
        BigDecimal safeExecutedQty = executedQuantity != null ? executedQuantity : BigDecimal.ZERO;
        return originalQuantity.subtract(safeExecutedQty);
    }

    /**
     * Переводит ордер в статус REJECTED (отклонен).
     */
    public void markAsRejected(String reason) {
        validateTransition(OrderStatus.REJECTED);
        this.status = OrderStatus.REJECTED;
    }

    /**
     * Переводит ордер в статус CANCELED (отменен).
     */
    public void markAsCancelled() {
        validateTransition(OrderStatus.CANCELED);
        this.status = OrderStatus.CANCELED;
    }

    private void validateTransition(OrderStatus newStatus) {
        if (isTerminal()) {
            throw new IllegalStateException(String.format(
                "Невозможный переход из терминального состояния %s в %s для ордера %s", 
                this.status, newStatus, this.id));
        }
    }

    /**
     * Переводит ордер в статус SENT_TO_EXCHANGE (отправлен в шлюз биржи).
     */
    public void markAsSentToExchange() {
        if (this.status != OrderStatus.EXECUTING) {
            throw new IllegalStateException("Invalid transition to SENT_TO_EXCHANGE from " + status);
        }
        this.status = OrderStatus.SENT_TO_EXCHANGE;
    }

    /**
     * Переводит ордер в статус EXECUTING (захват для исполнения).
     */    public void markExecuting() {
        if (this.status != OrderStatus.PENDING_EXECUTION && this.status != OrderStatus.EXECUTING) {
            throw new IllegalStateException(String.format(
                "Невозможный переход в EXECUTING из %s для ордера %s", 
                this.status, this.id));
        }
        this.status = OrderStatus.EXECUTING;
    }

    public boolean isTerminal() {
        return status == OrderStatus.FILLED || 
               status == OrderStatus.REJECTED || 
               status == OrderStatus.CANCELED ||
               status == OrderStatus.ERROR;
    }

    /**
     * Проверяет, завис ли ордер в процессе исполнения.
     */
    public boolean isStuck(Instant threshold) {
        return status == OrderStatus.EXECUTING;
    }



    public BigDecimal getQuantity() {
        return originalQuantity;
    }
}
