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
    @DisplayName("EXECUTING -> FILLED корректное исполнение")
    void testOrderFill() {
        Order order = createOrder();
        order.markExecuting();

        order.fill("EX-123", new BigDecimal("1.0"), new BigDecimal("101.0"));

        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("1.0"), order.getExecutedQuantity());
        assertEquals(0, new BigDecimal("101.0").compareTo(order.getAveragePrice()));
    }

    @Test
    @DisplayName("partial fill корректно агрегирует исполнение")
    void testPartialFill() {
        Order order = createOrder();
        order.markExecuting();

        order.applyPartialFill(new BigDecimal("0.3"), new BigDecimal("101.0"));

        assertEquals(new BigDecimal("0.3"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("0.7"), order.getRemainingQuantity());
    }

    @Test
    @DisplayName("terminal state блокирует переходы")
    void testTerminalStateTransitions() {
        Order order = createOrder();
        order.markExecuting();
        order.fill("EX-1", new BigDecimal("1.0"), new BigDecimal("100.5"));

        assertThrows(IllegalStateException.class, order::markExecuting);
        assertThrows(IllegalStateException.class, () -> order.markAsUnknown());
    }

    @Test
    @DisplayName("EXECUTING -> FILLED нельзя повторно применять fill с другим статусом")
    void shouldPreventInvalidFillAfterTerminal() {
        Order order = createOrder();
        order.markExecuting();
        order.fill("EX-1", new BigDecimal("1.0"), new BigDecimal("100.0"));

        assertThrows(IllegalStateException.class,
                () -> order.fill("EX-2", new BigDecimal("1.0"), new BigDecimal("101.0")));
    }

    @Test
    @DisplayName("markAsRejected возможен только после EXECUTING")
    void rejectOnlyFromExecuting() {
        Order order = createOrder();

        // ❗ важно: теперь сначала EXECUTING
        order.markExecuting();

        assertDoesNotThrow(() -> order.markAsRejected("risk"));
    }

    @Test
    @DisplayName("UNKNOWN state recovery разрешает fill")
    void unknownAllowsFill() {
        Order order = createOrder();
        order.markAsUnknown();

        assertDoesNotThrow(() ->
                order.fill("EX-1", new BigDecimal("1.0"), new BigDecimal("102.0"))
        );
    }

    @Test
    @DisplayName("UNKNOWN разрешает reject")
    void unknownAllowsReject() {
        Order order = createOrder();
        order.markAsUnknown();

        assertDoesNotThrow(() -> order.markAsRejected("ambiguous"));
    }

    private Order createOrder() {
        return Order.builder()
                .id(UUID.randomUUID())
                .clientOrderId("C1-" + UUID.randomUUID())
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .originalQuantity(new BigDecimal("1.0"))
                .price(new BigDecimal("100.0"))
                .status(OrderStatus.PENDING_EXECUTION)
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .build();
    }
}