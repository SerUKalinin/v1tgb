package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ExecutionIdentityMismatchTest {

    @Test
    void orderCreatedWithForeignExecutionIdMustBeRejectedBeforeClaim() {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "IDENTITY-MISMATCH-" + orderId,
                        "BTCUSDT",
                        com.tradingbot.common.enums.OrderSide.BUY,
                        com.tradingbot.common.enums.OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "identity-mismatch-test",
                        signalId
                );

        UUID canonicalExecutionId =
                order.getExecutionId();

        UUID foreignExecutionId =
                UUID.randomUUID();

        /*
         * ExecutionContext intentionally carries
         * an executionId belonging to another lifecycle.
         */
        ExecutionContext foreignContext =
                new ExecutionContext(
                        new IdentityContext(
                                signalId,
                                signalId
                        ),
                        ExecutionAttemptContext.recover(
                                UUID.randomUUID(),
                                foreignExecutionId,
                                1
                        ),
                        BusinessContext.of(
                                orderId.toString()
                        )
                );

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        canonicalExecutionId
                );

        SystemStateManager stateManager =
                mock(SystemStateManager.class);

        ExecutionClaimPort executionClaimPort =
                mock(ExecutionClaimPort.class);

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        TransitionValidator transitionValidator =
                mock(TransitionValidator.class);

        when(
                stateManager.isReady()
        ).thenReturn(true);

        OrderExecutionClaimService claimService =
                new OrderExecutionClaimService(
                        stateManager,
                        executionClaimPort,
                        orderRepository,
                        transitionValidator
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                claimService.claim(
                                        null,
                                        foreignContext,
                                        payload
                                )
                );

        assertEquals(
                true,
                exception.getMessage()
                        .contains("Identity mismatch")
        );

        /*
         * Canonical Order executionId must remain untouched.
         */
        assertEquals(
                canonicalExecutionId,
                order.getExecutionId()
        );

        /*
         * Foreign execution must not acquire ownership.
         */
        verify(
                orderRepository,
                never()
        ).claimForExecution(
                any(),
                any()
        );

        verify(
                executionClaimPort,
                never()
        ).claimExecution(
                any(),
                any()
        );
    }

    @Test
    void domainOrderMustRejectMutationWithForeignExecutionContext() {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "DOMAIN-IDENTITY-MISMATCH-" + orderId,
                        "BTCUSDT",
                        com.tradingbot.common.enums.OrderSide.BUY,
                        com.tradingbot.common.enums.OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "identity-mismatch-test",
                        signalId
                );

        UUID canonicalExecutionId =
                order.getExecutionId();

        UUID foreignExecutionId =
                UUID.randomUUID();

        ExecutionContext foreignContext =
                new ExecutionContext(
                        IdentityContext.of(signalId),
                        ExecutionAttemptContext.recover(
                                UUID.randomUUID(),
                                foreignExecutionId,
                                1
                        ),
                        BusinessContext.of(
                                orderId.toString()
                        )
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                order.markExecuting(
                                        foreignContext
                                )
                );

        assertEquals(
                true,
                exception.getMessage()
                        .contains("Execution identity mismatch")
        );

        /*
         * No lifecycle mutation is allowed.
         */
        assertEquals(
                com.tradingbot.common.enums.OrderStatus.PENDING_EXECUTION,
                order.getStatus()
        );

        assertEquals(
                canonicalExecutionId,
                order.getExecutionId()
        );

        assertEquals(
                0,
                order.getExecutionAttempts()
        );
    }
}