package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
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
public class OrderExecutedEvent {
    IdentityContext identity;
    ExecutionAttemptContext attempt;
    BusinessContext business;
    UUID eventId;
    UUID orderId;
    UUID signalId;
    UUID executionId;
    String symbol;
    BigDecimal quantity;
    BigDecimal price;
    OrderStatus status;
    Instant timestamp;
    String strategyId;

    public static OrderExecutedEvent from(Order order) {
        Objects.requireNonNull(order);
        Objects.requireNonNull(order.getId());
        Objects.requireNonNull(order.getSignalId());
        Objects.requireNonNull(order.getExecutionId());

        IdentityContext identity = new IdentityContext(order.getSignalId(), order.getSignalId());
        ExecutionAttemptContext attempt = new ExecutionAttemptContext(order.getExecutionId(), order.getExecutionId(), 1);
        BusinessContext business = BusinessContext.of(order.getId().toString());

        return new OrderExecutedEvent(
                identity,
                attempt,
                business,
                UUID.randomUUID(),
                order.getId(),
                order.getSignalId(),
                order.getExecutionId(),
                order.getSymbol(),
                order.getQuantity(),
                order.getPrice(),
                order.getStatus(),
                Instant.now(),
                order.getStrategyId()
        );
    }
}
