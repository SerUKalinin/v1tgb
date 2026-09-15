package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    @Test
    @DisplayName("EXECUTING -> FILLED корректное исполнение")
    void testOrderFill() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);

        order.fill(
                context,
                "EX-123",
                new BigDecimal("1.0"),
                new BigDecimal("101.0")
        );

        assertEquals(
                OrderStatus.FILLED,
                order.getStatus()
        );

        assertEquals(
                new BigDecimal("1.0"),
                order.getExecutedQuantity()
        );

        assertEquals(
                0,
                new BigDecimal("101.0")
                        .compareTo(order.getAveragePrice())
        );
    }

    @Test
    @DisplayName("partial fill корректно агрегирует исполнение")
    void testPartialFill() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);

        order.applyPartialFill(
                context,
                new BigDecimal("0.3"),
                new BigDecimal("101.0")
        );

        assertEquals(
                new BigDecimal("0.3"),
                order.getExecutedQuantity()
        );

        assertEquals(
                new BigDecimal("0.7"),
                order.getRemainingQuantity()
        );
    }

    @Test
    @DisplayName("terminal state блокирует переходы")
    void testTerminalStateTransitions() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);

        order.fill(
                context,
                "EX-1",
                new BigDecimal("1.0"),
                new BigDecimal("100.5")
        );

        ExecutionContext sameExecutionContext = testContext(order);

        assertThrows(
                IllegalStateException.class,
                () -> order.markExecuting(sameExecutionContext)
        );

        assertThrows(
                IllegalStateException.class,
                () -> order.markAsUnknown(sameExecutionContext)
        );
    }

    @Test
    @DisplayName("EXECUTING -> FILLED: повторный fill идемпотентен")
    void shouldPreventInvalidFillAfterTerminal() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);

        order.fill(
                context,
                "EX-1",
                new BigDecimal("1.0"),
                new BigDecimal("100.0")
        );

        /*
         * Используем тот же executionId Order.
         * Повторный fill должен быть идемпотентным.
         */
        ExecutionContext sameExecutionContext = testContext(order);

        order.fill(
                sameExecutionContext,
                "EX-2",
                new BigDecimal("1.0"),
                new BigDecimal("101.0")
        );

        assertEquals(
                OrderStatus.FILLED,
                order.getStatus()
        );

        assertEquals(
                new BigDecimal("1.0"),
                order.getExecutedQuantity()
        );

        assertEquals(
                0,
                new BigDecimal("100.0")
                        .compareTo(order.getAveragePrice())
        );

        assertEquals(
                "EX-1",
                order.getExchangeOrderId()
        );
    }

    @Test
    @DisplayName("markAsRejected возможен только после EXECUTING")
    void rejectOnlyFromExecuting() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);

        assertDoesNotThrow(
                () -> order.markAsRejected(
                        context,
                        "risk"
                )
        );
    }

    @Test
    @DisplayName("UNKNOWN state recovery разрешает fill")
    void unknownAllowsFill() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);
        order.markAsUnknown(context);

        assertDoesNotThrow(
                () -> order.fill(
                        context,
                        "EX-1",
                        new BigDecimal("1.0"),
                        new BigDecimal("102.0")
                )
        );
    }

    @Test
    @DisplayName("UNKNOWN разрешает reject")
    void unknownAllowsReject() {
        Order order = createOrder();
        ExecutionContext context = testContext(order);

        order.markExecuting(context);
        order.markAsUnknown(context);

        assertDoesNotThrow(
                () -> order.markAsRejected(
                        context,
                        "ambiguous"
                )
        );
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

    private ExecutionContext testContext(Order order) {
        return ExecutionContext.of(order);
    }
}
