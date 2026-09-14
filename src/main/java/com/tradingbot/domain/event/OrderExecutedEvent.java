package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable DTO доменного события ORDER_EXECUTED.
 *
 * <p>Фиксирует факт исполнения ордера и является частью контрактного слоя
 * согласно SYSTEM_CONTRACT.md.</p>
 *
 * <p>Используется для:
 * <ul>
 *     <li>Outbox публикации</li>
 *     <li>аудита исполнения</li>
 *     <li>reconciliation и восстановления состояния</li>
 * </ul>
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

    /**
     * Создаёт событие исполнения ордера из доменной модели.
     *
     * <p>Важно: используется ExecutionContext как единственный источник
     * идентичности события (SSOT для tracing).</p>
     *
     * @param order доменный ордер
     * @return OrderExecutedEvent
     */
    public static OrderExecutedEvent from(Order order) {

        ExecutionContext context = ExecutionContext.of(order);

        return OrderExecutedEvent.builder()
                .identity(context.identity())
                .attempt(context.attempt())
                .business(context.business())
                .orderId(order.getId())
                .symbol(order.getSymbol())
                .quantity(order.getExecutedQuantity())
                .price(order.getAveragePrice())
                .status(order.getStatus())
                .rejectionReason(order.getRejectionReason())
                .timestamp(Instant.now())
                .build();
    }
}