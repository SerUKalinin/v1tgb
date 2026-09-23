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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationRecoveryLifecycleTest {

    private OrderRepositoryPort orderRepository;
    private ExchangeOrderQueryService exchangeQueryService;
    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {

        orderRepository =
                mock(OrderRepositoryPort.class);

        exchangeQueryService =
                mock(ExchangeOrderQueryService.class);

        reconciliationService =
                new ReconciliationService(
                        orderRepository,
                        mock(OutboxRecoveryPort.class),
                        exchangeQueryService,
                        mock(OrderCompensationService.class),
                        mock(RiskEngine.class),
                        mock(AdminNotificationService.class),
                        mock(PositionRebuildService.class),
                        mock(SystemStateManager.class),
                        mock(TransitionValidator.class),
                        mock(ExecutionLogger.class)
                );
    }

    @Test
    void recoveringAcceptedMustMoveToSentToExchange() {

        Order order =
                createUnknownOrder(
                        "client-recovery-accepted"
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        when(
                orderRepository.claimForReconciliation(
                        eq(order.getId())
                )
        ).thenAnswer(invocation -> {

            order.markRecovering(context);

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq(order.getSymbol()),
                        eq(order.getClientOrderId())
                )
        ).thenReturn(
                ExecutionResult.accepted(
                        "exchange-123",
                        order.getClientOrderId()
                )
        );

        UUID executionId =
                order.getExecutionId();

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.SENT_TO_EXCHANGE,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        assertEquals(
                "exchange-123",
                order.getExchangeOrderId()
        );

        verify(
                orderRepository,
                times(1)
        ).save(eq(order));
    }

    @Test
    void recoveringPartialFillMustMoveToPartiallyFilled() {

        Order order =
                createUnknownOrder(
                        "client-recovery-partial"
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        when(
                orderRepository.claimForReconciliation(
                        eq(order.getId())
                )
        ).thenAnswer(invocation -> {

            order.markRecovering(context);

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq(order.getSymbol()),
                        eq(order.getClientOrderId())
                )
        ).thenReturn(
                ExecutionResult.partiallyFilled(
                        order.getId(),
                        "exchange-123",
                        "trade-123",
                        order.getSymbol(),
                        order.getSide(),
                        new BigDecimal("0.30000000"),
                        new BigDecimal("100.00000000"),
                        order.getClientOrderId()
                )
        );

        UUID executionId =
                order.getExecutionId();

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        assertEquals(
                0,
                new BigDecimal("0.30000000")
                        .compareTo(
                                order.getExecutedQuantity()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("100.00000000")
                        .compareTo(
                                order.getAveragePrice()
                        )
        );

        verify(
                orderRepository,
                times(1)
        ).save(eq(order));
    }

    @Test
    void repeatedPartialRecoveryMustUpdateCumulativeQuantity() {

        Order order =
                createUnknownOrder(
                        "client-recovery-partial-update"
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        when(
                orderRepository.claimForReconciliation(
                        eq(order.getId())
                )
        ).thenAnswer(invocation -> {

            if (order.getStatus() == OrderStatus.UNKNOWN) {
                order.markRecovering(context);
            }

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq(order.getSymbol()),
                        eq(order.getClientOrderId())
                )
        ).thenReturn(
                ExecutionResult.partiallyFilled(
                        order.getId(),
                        "exchange-123",
                        "trade-1",
                        order.getSymbol(),
                        order.getSide(),
                        new BigDecimal("0.30000000"),
                        new BigDecimal("100.00000000"),
                        order.getClientOrderId()
                ),
                ExecutionResult.partiallyFilled(
                        order.getId(),
                        "exchange-123",
                        "trade-2",
                        order.getSymbol(),
                        order.getSide(),
                        new BigDecimal("0.60000000"),
                        new BigDecimal("101.00000000"),
                        order.getClientOrderId()
                )
        );

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                order.getStatus()
        );

        assertEquals(
                0,
                new BigDecimal("0.30000000")
                        .compareTo(
                                order.getExecutedQuantity()
                        )
        );

        /*
         * Second reconciliation contains cumulative quantity 0.6.
         */
        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                order.getStatus()
        );

        assertEquals(
                0,
                new BigDecimal("0.60000000")
                        .compareTo(
                                order.getExecutedQuantity()
                        )
        );

        assertEquals(
                0,
                new BigDecimal("101.00000000")
                        .compareTo(
                                order.getAveragePrice()
                        )
        );

        verify(
                orderRepository,
                times(2)
        ).save(eq(order));
    }

    @Test
    void recoveringUnknownMustRemainUnknownWhenExchangeIsStillAmbiguous() {

        Order order =
                createUnknownOrder(
                        "client-recovery-unknown"
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        when(
                orderRepository.claimForReconciliation(
                        eq(order.getId())
                )
        ).thenAnswer(invocation -> {

            order.markRecovering(context);

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq(order.getSymbol()),
                        eq(order.getClientOrderId())
                )
        ).thenReturn(
                ExecutionResult.exchangeStateUnknown(
                        order.getId()
                )
        );

        UUID executionId =
                order.getExecutionId();

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.UNKNOWN,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        verify(
                orderRepository,
                times(1)
        ).save(eq(order));
    }

    @Test
    void recoveringFilledMustRemainSameExecutionLifecycle() {

        Order order =
                createUnknownOrder(
                        "client-recovery-filled"
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        when(
                orderRepository.claimForReconciliation(
                        eq(order.getId())
                )
        ).thenAnswer(invocation -> {

            order.markRecovering(context);

            return Optional.of(order);
        });

        when(
                exchangeQueryService.getOrderStatus(
                        eq(order.getSymbol()),
                        eq(order.getClientOrderId())
                )
        ).thenReturn(
                ExecutionResult.filled(
                        order.getId(),
                        "exchange-456",
                        "trade-456",
                        order.getSymbol(),
                        order.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100.00000000"),
                        BigDecimal.ZERO,
                        "USDT",
                        order.getClientOrderId()
                )
        );

        UUID executionId =
                order.getExecutionId();

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.FILLED,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        assertNotNull(
                order.getExecutedQuantity()
        );

        assertNotNull(
                order.getAveragePrice()
        );
    }

    private Order createUnknownOrder(
            String clientOrderId
    ) {

        Order order =
                Order.createPendingExecution(
                        UUID.randomUUID(),
                        clientOrderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("100.00000000"),
                        "strategy-1",
                        UUID.randomUUID()
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        order.markExecuting(context);
        order.markAsUnknown(context);

        assertEquals(
                OrderStatus.UNKNOWN,
                order.getStatus()
        );

        return order;
    }
}