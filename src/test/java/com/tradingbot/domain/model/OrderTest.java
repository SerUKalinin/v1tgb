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
    @DisplayName("Несколько частичных исполнений должны корректно накапливать объем и считать среднюю цену")
    void testMultiplePartialFills() {
        // Given
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        order.markExecuting();

        // When: First fill 0.3 @ 101
        order.markAsPartiallyFilled(new BigDecimal("0.3"), new BigDecimal("101.0"));
        
        // Then
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.getStatus());
        assertEquals(new BigDecimal("0.3"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("0.7"), order.getRemainingQuantity());
        assertEquals(0, new BigDecimal("101.0").compareTo(order.getAveragePrice()));

        // When: Second fill 0.5 @ 105
        order.markAsPartiallyFilled(new BigDecimal("0.5"), new BigDecimal("105.0"));

        // Then
        assertEquals(new BigDecimal("0.8"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("0.2"), order.getRemainingQuantity());
        // Avg price: (0.3 * 101 + 0.5 * 105) / 0.8 = (30.3 + 52.5) / 0.8 = 82.8 / 0.8 = 103.5
        assertEquals(0, new BigDecimal("103.5").compareTo(order.getAveragePrice()));
        
        // When: Final fill 0.2 @ 100
        order.markAsPartiallyFilled(new BigDecimal("0.2"), new BigDecimal("100.0"));
        
        // Then
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("1.0"), order.getExecutedQuantity());
        assertEquals(BigDecimal.ZERO.stripTrailingZeros(), order.getRemainingQuantity().stripTrailingZeros());
    }

    @Test
    @DisplayName("Инвариант: исполненный объем не может превышать исходный")
    void testOverfillProtection() {
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        order.markExecuting();

        assertThrows(IllegalStateException.class, () -> {
            order.markAsPartiallyFilled(new BigDecimal("1.1"), new BigDecimal("100.0"));
        });
    }
    @Test
    @DisplayName("Запрет переходов из терминальных состояний")
    void testTerminalStateTransitions() {
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        order.markExecuting();
        order.markAsCancelled();

        assertThrows(IllegalStateException.class, () -> order.markExecuting());
        assertThrows(IllegalStateException.class, () -> order.markAsFilled("EX1", new BigDecimal("1.0"), new BigDecimal("100.0")));
        assertThrows(IllegalStateException.class, () -> order.markAsRejected("Reason"));
    }

    @Test
    @DisplayName("Идемпотентность перехода в EXECUTING")
    void testIdempotentClaim() {
        Order order = createPendingOrder(new BigDecimal("1.0"), new BigDecimal("100.0"));
        
        order.markExecuting();
        assertEquals(OrderStatus.EXECUTING, order.getStatus());
        
        // Повторный вызов не должен бросать исключение
        assertDoesNotThrow(order::markExecuting);
        assertEquals(OrderStatus.EXECUTING, order.getStatus());
    }

    private Order createPendingOrder(BigDecimal qty, BigDecimal price) {
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
