package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExecutionOwnershipException;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class StaleExecutionCommitTest {

    @Test
    void staleExecutionMustNotCommitAfterAuthoritativeRecoveryToRejected() {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "STALE-COMMIT-REJECTED-" + orderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "stale-execution-test",
                        signalId
                );

        ExecutionContext executionContext =
                ExecutionContext.of(order);

        /*
         * The original execution owns the lifecycle.
         */
        order.markExecuting(
                executionContext
        );

        UUID executionId =
                order.getExecutionId();

        /*
         * Authoritative recovery wins and finalizes the order.
         */
        order.markAsRejected(
                executionContext,
                "AUTHORITATIVE_RECOVERY_REJECT"
        );

        assertEquals(
                OrderStatus.REJECTED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        OrderExecutionCommitService commitService =
                new OrderExecutionCommitService(
                        orderRepository,
                        mock(OrderCompensationService.class),
                        mock(OutboxService.class),
                        mock(ExecutionLockService.class)
                );

        ExecutionResult lateExchangeResult =
                ExecutionResult.filled(
                        orderId,
                        "EXCHANGE-STALE",
                        "TRADE-STALE",
                        "BTCUSDT",
                        OrderSide.BUY,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        order.getClientOrderId()
                );

        /*
         * The old execution attempts to commit after
         * authoritative recovery already finalized the order.
         *
         * Contract:
         * stale execution MUST NOT mutate terminal order.
         */
        ExecutionOwnershipException exception =
                assertThrows(
                        ExecutionOwnershipException.class,
                        () ->
                                commitService.commit(
                                        null,
                                        executionContext,
                                        order,
                                        lateExchangeResult,
                                        "STALE-LOCK-" + executionId
                                )
                );

        assertEquals(
                OrderStatus.REJECTED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        verifyNoInteractions(
                orderRepository
        );
    }

    @Test
    void staleExecutionMustNotCommitAfterAuthoritativeRecoveryToCanceled() {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "STALE-COMMIT-CANCELED-" + orderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "stale-execution-test",
                        signalId
                );

        ExecutionContext executionContext =
                ExecutionContext.of(order);

        order.markExecuting(
                executionContext
        );

        UUID executionId =
                order.getExecutionId();

        /*
         * Authoritative recovery cancels the exchange order.
         */
        order.markCancelled(
                executionContext
        );

        assertEquals(
                OrderStatus.CANCELED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        OrderExecutionCommitService commitService =
                new OrderExecutionCommitService(
                        orderRepository,
                        mock(OrderCompensationService.class),
                        mock(OutboxService.class),
                        mock(ExecutionLockService.class)
                );

        ExecutionResult lateExchangeResult =
                ExecutionResult.filled(
                        orderId,
                        "EXCHANGE-LATE",
                        "TRADE-LATE",
                        "BTCUSDT",
                        OrderSide.BUY,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        order.getClientOrderId()
                );

        assertThrows(
                ExecutionOwnershipException.class,
                () ->
                        commitService.commit(
                                null,
                                executionContext,
                                order,
                                lateExchangeResult,
                                "STALE-LOCK-" + executionId
                        )
        );

        assertEquals(
                OrderStatus.CANCELED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        verifyNoInteractions(
                orderRepository
        );
    }
}