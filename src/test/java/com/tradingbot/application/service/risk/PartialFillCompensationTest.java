package com.tradingbot.application.service.risk;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PartialFillCompensationTest {

    private RiskEngine riskEngine;
    private OrderCompensationService compensationService;

    @BeforeEach
    void setUp() {

        riskEngine =
                mock(RiskEngine.class);

        compensationService =
                new OrderCompensationService(
                        riskEngine
                );
    }

    @Test
    void partialFillMustReleaseOnlyRemainingReservation() {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "CLIENT-" + orderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("1.0"),
                        new BigDecimal("100.0"),
                        "strategy-1",
                        signalId
                );

        /*
         * Полностью детерминированный executionId.
         */
        UUID executionId =
                IdentityFactory.deriveExecution(
                        orderId,
                        1
                );

        ReflectionTestUtils.setField(
                order,
                "executionId",
                executionId
        );

        ExecutionContext context =
                ExecutionContext.of(order);

        /*
         * PENDING_EXECUTION -> EXECUTING
         */
        order.markExecuting(
                context
        );

        /*
         * Исполнили только 0.3 из 1.0 BTC.
         *
         * Reservation:
         *     1.0 * 100 = 100 USDT
         *
         * Executed:
         *     0.3 * 100 = 30 USDT
         *
         * Remaining:
         *     0.7 BTC
         *
         * Release:
         *     0.7 * 100 = 70 USDT
         */
        order.applyPartialFill(
                context,
                new BigDecimal("0.3"),
                new BigDecimal("100.0")
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                order.getStatus()
        );

        assertEquals(
                0,
                new BigDecimal("0.3")
                        .compareTo(
                                order.getExecutedQuantity()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("0.7")
                        .compareTo(
                                order.getRemainingQuantity()
                        ),
                "Remaining quantity must be 0.7 BTC"
        );

        /*
         * Вызываем реальный production compensation service.
         */
        compensationService.releasePartial(
                order,
                order.getExecutedQuantity()
        );

        /*
         * Должно быть освобождено именно:
         *
         * 0.7 * 100 = 70 USDT
         */
        verify(
                riskEngine,
                times(1)
        ).release(
                eq(context),
                argThat(amount ->
                        amount != null
                                && amount.compareTo(new BigDecimal("70.0")) == 0
                ),
                eq("Partial fill compensation")
        );
    }
}