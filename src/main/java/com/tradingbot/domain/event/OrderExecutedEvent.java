package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
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

    /**
     * Explicit Factory для создания события из доменных объектов.
     * Гарантирует отсутствие null-идентификаторов и корректный маппинг.
     */
    public static OrderExecutedEvent from(Order order) {
        Objects.requireNonNull(order);
        Objects.requireNonNull(order.getId());
        Objects.requireNonNull(order.getSignalId());
        Objects.requireNonNull(order.getExecutionId());

        return new OrderExecutedEvent(
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
