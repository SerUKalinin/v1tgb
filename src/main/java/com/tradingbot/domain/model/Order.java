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
     * Изменяет состояние ордера. Доступ ограничен уровнем пакета для обеспечения
     * изменения состояния только через контролируемый Pipeline (StateTransitionExecutor).
     */
    public void updateStatus(OrderStatus targetStatus) {
        this.status = targetStatus;
    }

    public void markAsPartiallyFilled(BigDecimal executedQtyDelta, BigDecimal executionPrice) {
        if (executedQtyDelta == null || executedQtyDelta.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal currentExecutedQty = executedQuantity != null ? executedQuantity : BigDecimal.ZERO;
        BigDecimal currentAvgPrice = averagePrice != null ? averagePrice : BigDecimal.ZERO;
        BigDecimal newExecutedQuantity = currentExecutedQty.add(executedQtyDelta);

        if (newExecutedQuantity.compareTo(originalQuantity) > 0) {
            throw new IllegalStateException("Исполненный объем превышает исходный");
        }

        BigDecimal totalCost = currentAvgPrice.multiply(currentExecutedQty)
                .add(executionPrice.multiply(executedQtyDelta));

        this.averagePrice = totalCost.divide(newExecutedQuantity, 18, RoundingMode.HALF_UP);
        this.executedQuantity = newExecutedQuantity;
    }

    public void fill(String exchangeOrderId, BigDecimal executedQty, BigDecimal executionPrice) {
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executionPrice != null ? executionPrice : (this.price != null ? this.price : BigDecimal.ZERO);
    }

    public BigDecimal getRemainingQuantity() {
        BigDecimal executed = executedQuantity != null ? executedQuantity : BigDecimal.ZERO;
        return originalQuantity.subtract(executed);
    }

    public void markAsRejected(String reason) {}

    public BigDecimal getQuantity() { return originalQuantity; }
}
