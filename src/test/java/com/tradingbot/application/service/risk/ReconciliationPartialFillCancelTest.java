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
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationPartialFillCancelTest {

    private ReconciliationService reconciliationService;

    private OrderRepositoryPort orderRepository;
    private ExchangeOrderQueryService exchangeQueryService;
    private RiskEngine riskEngine;
    private OrderCompensationService compensationService;

    @BeforeEach
    void setUp() {

        orderRepository =
                mock(OrderRepositoryPort.class);

        exchangeQueryService =
                mock(ExchangeOrderQueryService.class);

        riskEngine =
                mock(RiskEngine.class);

        compensationService =
                new OrderCompensationService(
                        riskEngine
                );

        reconciliationService =
                new ReconciliationService(
                        orderRepository,
                        mock(OutboxEventRepository.class),
                        exchangeQueryService,
                        compensationService,
                        riskEngine,
                        mock(AdminNotificationService.class),
                        mock(PositionRebuildService.class),
                        mock(SystemStateManager.class),
                        mock(TransitionValidator.class),
                        mock(ExecutionLogger.class)
                );
    }

    @Test
    void partiallyFilledOrderCanceledByExchangeMustReleaseOnlyRemainingReservation()
            throws Exception {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-partial-cancel",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("1.0"),
                        new BigDecimal("100.0"),
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

        /*
         * PENDING_EXECUTION -> EXECUTING
         */
        order.markExecuting(
                context
        );

        /*
         * Exchange фактически исполнил только 0.3 BTC:
         *
         * Original:
         *     1.0 BTC
         *
         * Executed:
         *     0.3 BTC
         *
         * Remaining:
         *     0.7 BTC
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
                order.getRemainingQuantity()
                        .compareTo(
                                new BigDecimal("0.7")
                        )
        );

        /*
         * Reconciliation должен получить этот же Order.
         */
        when(
                orderRepository.claimForReconciliation(
                        eq(orderId)
                )
        ).thenReturn(
                Optional.of(order)
        );

        /*
         * Биржа теперь говорит:
         *
         * остаток ордера отменён.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        eq("client-partial-cancel")
                )
        ).thenReturn(
                ExecutionResult.canceled(
                        orderId
                )
        );

        /*
         * Выполняем reconciliation.
         */
        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        /*
         * PARTIALLY_FILLED -> CANCELED
         */
        assertEquals(
                OrderStatus.CANCELED,
                order.getStatus()
        );

        /*
         * Execution identity нельзя потерять.
         */
        assertEquals(
                executionId,
                order.getExecutionId()
        );

        /*
         * Критический финансовый invariant:
         *
         * Reservation = 1.0 * 100 = 100
         * Executed    = 0.3 * 100 = 30
         * Remaining   = 0.7 * 100 = 70
         *
         * При cancellation освобождаем ТОЛЬКО 70.
         */
        verify(
                riskEngine,
                times(1)
        ).release(
                eq(context),
                argThat(amount ->
                        amount != null
                                && amount.compareTo(
                                new BigDecimal("70")
                        ) == 0
                ),
                eq("Partial fill compensation")
        );

        /*
         * Не должно быть освобождения всех 100 USDT.
         */
        verify(
                riskEngine,
                never()
        ).release(
                eq(context),
                argThat(amount ->
                        amount != null
                                && amount.compareTo(
                                new BigDecimal("100")
                        ) == 0
                ),
                anyString()
        );

        /*
         * Не должно быть освобождения только исполненных 30 USDT.
         */
        verify(
                riskEngine,
                never()
        ).release(
                eq(context),
                argThat(amount ->
                        amount != null
                                && amount.compareTo(
                                new BigDecimal("30")
                        ) == 0
                ),
                anyString()
        );

        /*
         * Reconciliation сохраняет состояние:
         * 1) recovery/reconciliation step
         * 2) terminal CANCELED state
         */
        verify(
                orderRepository,
                times(1)
        ).save(
                eq(order)
        );

        verify(
                exchangeQueryService,
                times(1)
        ).getOrderStatus(
                eq("client-partial-cancel")
        );
    }
}