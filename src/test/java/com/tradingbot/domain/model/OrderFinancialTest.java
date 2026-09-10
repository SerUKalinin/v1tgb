package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderFinancialTest {

    @Test
    @DisplayName("Should correctly set executed quantity and price")
    void fillCalculationTest() {
        UUID id = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        Order order = Order.createPendingExecution(
                id,
                "test-" + UUID.randomUUID(),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("10.0"),
                new BigDecimal("100.0"),
                "STRAT-1",
                signalId
        );

        order.markExecuting(ExecutionContext.of(signalId));

        ExecutionContext context = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.empty()
        );

        order.fill(
                context,
                "EX-1",
                new BigDecimal("10.0"),
                new BigDecimal("105.0")
        );

        assertEquals(new BigDecimal("10.0"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("105.0"), order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }

    @Test
    @DisplayName("Should use execution price instead of limit price in fill")
    void markAsFilledPriceTest() {
        UUID id = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        Order order = Order.createPendingExecution(
                id,
                "test-" + UUID.randomUUID(),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("1.0"),
                new BigDecimal("100.0"),
                "STRAT-1",
                signalId
        );

        order.markExecuting(ExecutionContext.of(signalId));

        BigDecimal executionPrice = new BigDecimal("102.5");

        ExecutionContext context = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.empty()
        );

        order.fill(
                context,
                "EX-1",
                new BigDecimal("1.0"),
                executionPrice
        );

        assertEquals(executionPrice, order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }
}
