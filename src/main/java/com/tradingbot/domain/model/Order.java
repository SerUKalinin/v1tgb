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

    /**
     * Внутренний метод для обновления статуса.
     * Должен вызываться только через StateTransitionExecutor.
     */
    void updateStatus(OrderStatus targetStatus) {
        this.status = targetStatus;
    }

    public void applyStateTransition(OrderStatus targetStatus, com.tradingbot.application.service.execution.StateTransitionExecutor initiator) {
        this.status = targetStatus;
    }

    public OrderSnapshot toSnapshot() {        return new OrderSnapshot(
                id, clientOrderId, exchangeOrderId, symbol, side, type,
                originalQuantity, price, null, null,
                executedQuantity, averagePrice, strategyId, status
        );
    }

    public void fill(String exchangeOrderId, BigDecimal executedQty, BigDecimal executionPrice) {
        if (exchangeOrderId != null) {
            this.exchangeOrderId = exchangeOrderId;
        }
        this.executedQuantity = executedQty;
        this.averagePrice = executionPrice != null ? executionPrice : (this.price != null ? this.price : BigDecimal.ZERO);
    }

    public void markAsPartiallyFilled(BigDecimal executedQtyDelta, BigDecimal executionPrice) {
        BigDecimal currentExecutedQty = this.executedQuantity != null ? this.executedQuantity : BigDecimal.ZERO;
        BigDecimal newExecutedQuantity = currentExecutedQty.add(executedQtyDelta);

        BigDecimal currentAvgPrice = this.averagePrice != null ? this.averagePrice : BigDecimal.ZERO;

        // Расчет новой средней цены: (P_old * Q_old + P_new * Q_delta) / Q_total
        BigDecimal totalCost = currentAvgPrice.multiply(currentExecutedQty)
                .add(executionPrice.multiply(executedQtyDelta));

        if (newExecutedQuantity.compareTo(BigDecimal.ZERO) > 0) {
            this.averagePrice = totalCost.divide(newExecutedQuantity, 18, RoundingMode.HALF_UP);
        }
        this.executedQuantity = newExecutedQuantity;
    }

    public BigDecimal getRemainingQuantity() {
        BigDecimal executed = executedQuantity != null ? executedQuantity : BigDecimal.ZERO;
        return originalQuantity.subtract(executed);
    }

    public void markAsRejected(String reason) {
        // Логика обработки отклонения, если требуется
    }

    public BigDecimal getQuantity() {
        return originalQuantity;
    }
}
