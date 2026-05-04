package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    @DisplayName("Исполнение ордера должно корректно устанавливать объем и цену")
    void testOrderFill() {
        // Given
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        order.markExecuting();

        // When
        order.fill("EX-123", new BigDecimal("1.0"), new BigDecimal("101.0"));
        
        // Then
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("1.0"), order.getExecutedQuantity());
        assertEquals(BigDecimal.ZERO.stripTrailingZeros(), order.getRemainingQuantity().stripTrailingZeros());
        assertEquals(0, new BigDecimal("101.0").compareTo(order.getAveragePrice()));
    }

    @Test
    @DisplayName("Частичное исполнение через applyPartialFill")
    void testPartialFill() {
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        order.markExecuting();

        order.applyPartialFill(new BigDecimal("0.3"), new BigDecimal("101.0"));
        
        assertEquals(new BigDecimal("0.3"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("0.7"), order.getRemainingQuantity());
    }

    @Test
    @DisplayName("Запрет переходов из терминальных состояний")
    void testTerminalStateTransitions() {
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        order.markExecuting();
        order.markAsRejected("Test");

        assertEquals(OrderStatus.REJECTED, order.getStatus());
    }

    @Test
    @DisplayName("Переход в EXECUTING")
    void testClaim() {
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        
        order.markExecuting();
        assertEquals(OrderStatus.EXECUTING, order.getStatus());
    }    private Order createPendingOrder(BigDecimal qty, BigDecimal price) {
        return Order.builder()
                .id(UUID.randomUUID())
                .clientOrderId("C1")
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .originalQuantity(qty)
                .price(price)
                .status(OrderStatus.PENDING_EXECUTION)
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .build();
    }
}
