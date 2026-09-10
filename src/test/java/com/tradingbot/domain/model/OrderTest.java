package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.tracing.ExecutionContext;
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
        ExecutionContext ctx = testContext();
        order.markExecuting(ctx);

        order.fill(ctx, "EX-123", new BigDecimal("1.0"), new BigDecimal("101.0"));

        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("1.0"), order.getExecutedQuantity());
        assertEquals(0, new BigDecimal("101.0").compareTo(order.getAveragePrice()));
    }

    @Test
    @DisplayName("partial fill корректно агрегирует исполнение")
    void testPartialFill() {
        Order order = createOrder();
        ExecutionContext ctx = testContext();
        order.markExecuting(ctx);

        order.applyPartialFill(ctx, new BigDecimal("0.3"), new BigDecimal("101.0"));

        assertEquals(new BigDecimal("0.3"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("0.7"), order.getRemainingQuantity());
    }

    @Test
    @DisplayName("terminal state блокирует переходы")
    void testTerminalStateTransitions() {
        Order order = createOrder();
        ExecutionContext ctx = testContext();
        order.markExecuting(ctx);
        order.fill(ctx, "EX-1", new BigDecimal("1.0"), new BigDecimal("100.5"));

        ExecutionContext ctx2 = testContext();
        assertThrows(IllegalStateException.class, () -> order.markExecuting(ctx2));
        assertThrows(IllegalStateException.class, () -> order.markAsUnknown(ctx2));
    }

    @Test
    @DisplayName("EXECUTING -> FILLED: повторный fill идемпотентен")
    void shouldPreventInvalidFillAfterTerminal() {
        Order order = createOrder();
        ExecutionContext ctx = testContext();
        order.markExecuting(ctx);
        order.fill(ctx, "EX-1", new BigDecimal("1.0"), new BigDecimal("100.0"));

        // Повторный fill из терминального FILLED — идемпотентный NOOP
        ExecutionContext ctx2 = testContext();
        order.fill(ctx2, "EX-2", new BigDecimal("1.0"), new BigDecimal("101.0"));

        // Значения не изменились
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("1.0"), order.getExecutedQuantity());
        assertEquals(0, new BigDecimal("100.0").compareTo(order.getAveragePrice()));
        assertEquals("EX-1", order.getExchangeOrderId());
    }

    @Test
    @DisplayName("markAsRejected возможен только после EXECUTING")
    void rejectOnlyFromExecuting() {
        Order order = createOrder();
        ExecutionContext ctx = testContext();

        order.markExecuting(ctx);

        assertDoesNotThrow(() -> order.markAsRejected(ctx, "risk"));
    }

    @Test
    @DisplayName("UNKNOWN state recovery разрешает fill")
    void unknownAllowsFill() {
        Order order = createOrder();
        ExecutionContext ctx = testContext();
        // Политика разрешает UNKNOWN только из EXECUTING
        order.markExecuting(ctx);
        order.markAsUnknown(ctx);

        assertDoesNotThrow(() ->
                order.fill(ctx, "EX-1", new BigDecimal("1.0"), new BigDecimal("102.0"))
        );
    }

    @Test
    @DisplayName("UNKNOWN разрешает reject")
    void unknownAllowsReject() {
        Order order = createOrder();
        ExecutionContext ctx = testContext();
        // Политика разрешает UNKNOWN только из EXECUTING
        order.markExecuting(ctx);
        order.markAsUnknown(ctx);

        assertDoesNotThrow(() -> order.markAsRejected(ctx, "ambiguous"));
    }

    private Order createOrder() {
        return Order.createPendingExecution(
                UUID.randomUUID(),
                "C1-" + UUID.randomUUID(),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("1.0"),
                new BigDecimal("100.0"),
                "test-strategy",
                UUID.randomUUID()
        );
    }

    private ExecutionContext testContext() {
        return ExecutionContext.of(UUID.randomUUID());
    }
}
