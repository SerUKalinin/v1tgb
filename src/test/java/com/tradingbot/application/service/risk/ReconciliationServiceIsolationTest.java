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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconciliationServiceIsolationTest {

    private ReconciliationService reconciliationService;
    private OrderRepositoryPort orderRepository;
    private ExchangeOrderQueryService exchangeQueryService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepositoryPort.class);
        exchangeQueryService = mock(ExchangeOrderQueryService.class);

        reconciliationService = new ReconciliationService(
                orderRepository,
                mock(OutboxEventRepository.class),
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
    void shouldSkipReconciliationWhenClaimReturnsEmpty() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.createPendingExecution(
                orderId, "client-123", "BTCUSDT",
                OrderSide.BUY, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.TEN,
                "strategy-1", UUID.randomUUID());

        when(orderRepository.claimForReconciliation(orderId))
                .thenReturn(Optional.empty());

        reconciliationService.syncOrderWithExchange(order, mock(ExecutionContext.class));

        verify(exchangeQueryService, never()).getOrderStatus(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void shouldRecoverUnknownOrderToFilled() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Order order = Order.createPendingExecution(
                orderId, "client-123", "BTCUSDT",
                OrderSide.BUY, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.TEN,
                "strategy-1", signalId);

        // Simulate: order was sent to exchange, timed out → UNKNOWN
        order.assignExecutionOwner(executionId);
        ExecutionContext context = ExecutionContext.of(order);
        order.markExecuting(context);
        order.markAsUnknown(context);

        when(orderRepository.claimForReconciliation(orderId))
                .thenReturn(Optional.of(order));

        when(exchangeQueryService.getOrderStatus("client-123"))
                .thenReturn(ExecutionResult.filled(
                        orderId, "ex-123", "trade-123",
                        "BTCUSDT", OrderSide.BUY,
                        BigDecimal.ONE, BigDecimal.TEN,
                        BigDecimal.ZERO, "USDT", "client-123"));

        reconciliationService.syncOrderWithExchange(order, context);

        // syncOrderWithExchange сохраняет дважды:
        // 1) markRecovering (UNKNOWN → RECOVERING) — строка 181
        // 2) forceFill (RECOVERING → FILLED) — строка 236
        verify(orderRepository, times(2)).save(order);
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }

    @Test
    void shouldSkipReconciliationWhenOrderIsAlreadyFilled() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Order order = Order.createPendingExecution(
                orderId, "client-123", "BTCUSDT",
                OrderSide.BUY, OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.TEN,
                "strategy-1", signalId);

        // Move to FILLED terminal state
        order.assignExecutionOwner(executionId);
        ExecutionContext context = ExecutionContext.of(order);
        order.markExecuting(context);
        order.fill(context, "ex-123", BigDecimal.ONE, BigDecimal.TEN);

        when(orderRepository.claimForReconciliation(orderId))
                .thenReturn(Optional.empty());

        reconciliationService.syncOrderWithExchange(order, context);

        verify(exchangeQueryService, never()).getOrderStatus(any());
        verify(orderRepository, never()).save(any());
    }
}
