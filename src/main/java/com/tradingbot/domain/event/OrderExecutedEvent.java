package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable Event DTO для события ORDER_EXECUTED.
 * Строго соответствует SYSTEM_CONTRACT.md.
 */
@Value
@Builder
@AllArgsConstructor
public class OrderExecutedEvent {
    IdentityContext identity;
    ExecutionAttemptContext attempt;
    BusinessContext business;
    UUID orderId;
    String symbol;
    BigDecimal quantity;
    BigDecimal price;
    OrderStatus status;
    String rejectionReason;
    Instant timestamp;

    public static OrderExecutedEvent from(Order order) {
        ExecutionContext context = ExecutionContext.of(order);
        return new OrderExecutedEvent(
                context.identity(),
                context.attempt(),
                context.business(),
                order.getId(),
                order.getSymbol(),
                order.getQuantity(),
                order.getAveragePrice(),
                order.getStatus(),
                order.getRejectionReason(),
                Instant.now()
        );
    }
}
