package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxRecoveryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationRejectedCanceledTest {

    private ReconciliationService reconciliationService;

    private OrderRepositoryPort orderRepository;
    private ExchangeOrderQueryService exchangeQueryService;
    private OrderCompensationService orderCompensationService;

    @BeforeEach
    void setUp() {

        orderRepository =
                mock(OrderRepositoryPort.class);

        exchangeQueryService =
                mock(ExchangeOrderQueryService.class);

        orderCompensationService =
                mock(OrderCompensationService.class);

        reconciliationService =
                new ReconciliationService(
                        orderRepository,
                        mock(OutboxRecoveryPort.class),
                        exchangeQueryService,
                        orderCompensationService,
                        mock(RiskEngine.class),
                        mock(AdminNotificationService.class),
                        mock(PositionRebuildService.class),
                        mock(SystemStateManager.class),
                        mock(TransitionValidator.class),
                        mock(ExecutionLogger.class)
                );
    }

    @Test
    void shouldRecoverUnknownOrderToRejectedAndReleaseReservation()
            throws Exception {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-rejected",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("50000"),
                        "strategy-1",
                        signalId
                );

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

        order.markExecuting(context);
        order.markAsUnknown(context);

        assertEquals(
                OrderStatus.UNKNOWN,
                order.getStatus()
        );

        when(
                orderRepository.claimForReconciliation(
                        eq(orderId)
                )
        ).thenAnswer(invocation -> {

            order.markRecovering(context);

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq("BTCUSDT"),
                        eq("client-rejected")
                )
        ).thenReturn(
                ExecutionResult.rejected(
                        orderId,
                        "TEST_REJECTED"
                )
        );

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.REJECTED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        assertNotNull(
                order.getExecutionStartedAt()
        );

        verify(
                orderCompensationService,
                times(1)
        ).releasePartial(
                eq(order),
                eq(order.getExecutedQuantity())
        );

        verify(
                orderRepository,
                times(1)
        ).save(eq(order));

        verify(
                exchangeQueryService,
                times(1)
        ).getOrderStatus(
                eq("BTCUSDT"),
                eq("client-rejected")
        );
    }

    @Test
    void shouldRecoverUnknownOrderToCanceledAndReleaseReservation()
            throws Exception {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-canceled",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("50000"),
                        "strategy-1",
                        signalId
                );

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

        order.markExecuting(context);
        order.markAsUnknown(context);

        assertEquals(
                OrderStatus.UNKNOWN,
                order.getStatus()
        );

        when(
                orderRepository.claimForReconciliation(
                        eq(orderId)
                )
        ).thenAnswer(invocation -> {

            order.markRecovering(context);

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq("BTCUSDT"),
                        eq("client-canceled")
                )
        ).thenReturn(
                ExecutionResult.canceled(
                        orderId
                )
        );

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.CANCELED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        verify(
                orderCompensationService,
                times(1)
        ).releasePartial(
                eq(order),
                eq(order.getExecutedQuantity())
        );

        verify(
                orderRepository,
                times(1)
        ).save(eq(order));

        verify(
                exchangeQueryService,
                times(1)
        ).getOrderStatus(
                eq("BTCUSDT"),
                eq("client-canceled")
        );
    }
}