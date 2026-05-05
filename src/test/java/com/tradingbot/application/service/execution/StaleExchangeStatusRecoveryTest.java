package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaleExchangeStatusRecoveryTest {

    private OrderExecutionHandler handler;
    private ExecutionPort executionPort;
    private OrderRepositoryPort orderRepository;
    private ExecutionLockService lockService;
    private TransitionValidator transitionValidator;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepositoryPort.class);
        lockService = mock(ExecutionLockService.class);
        transitionValidator = mock(TransitionValidator.class);

        SystemStateManager stateManager = mock(SystemStateManager.class);
        when(stateManager.isReady()).thenReturn(true);

        handler = new OrderExecutionHandler(
                executionPort,
                orderRepository,
                stateManager,
                mock(IdempotencyService.class),
                lockService,
                mock(RiskEngine.class),
                mock(OutboxService.class),
                transitionValidator
        );
    }

    @Test
    @DisplayName("System MUST NOT duplicate order if exchange status is temporarily stale (TIMEOUT/NOT_FOUND)")
    void shouldRecoverFromStaleExchangeStatusWithoutDuplicatePlacement() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String clientOrderId = "CL-" + orderId;

        Order order = Order.builder()
                .id(orderId)
                .clientOrderId(clientOrderId)
                .status(OrderStatus.PENDING_EXECUTION)
                .symbol("BTCUSDT")
                .originalQuantity(BigDecimal.ONE)
                .build();

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(orderId)
                .build();

        when(orderRepository.claimForExecution(orderId)).thenReturn(Optional.of(order));
        when(transitionValidator.isTerminal(any())).thenReturn(false);
        when(transitionValidator.isStale(any(), any())).thenReturn(false);

        // Симулируем RECOVERY путь (isNewExecution = false)
        String lockKey = "EXEC_ORDER_" + orderId;
        when(lockService.getLockState(lockKey)).thenReturn("PENDING");
        when(lockService.tryEnterExecuting(lockKey)).thenReturn(false);

        // Биржа: сначала таймаут (lag), потом SUCCESS
        ExecutionResult staleResult = ExecutionResult.builder()
                .status(ExecutionResult.Status.TIMEOUT)
                .build();

        ExecutionResult successResult = ExecutionResult.builder()
                .status(ExecutionResult.Status.SUCCESS)
                .exchangeOrderId("EX-123")
                .executedQty(BigDecimal.ONE)
                .executedPrice(new BigDecimal("50000"))
                .build();

        when(executionPort.getOrderStatus(clientOrderId))
                .thenReturn(staleResult)   // первый вызов
                .thenReturn(staleResult)   // retry
                .thenReturn(successResult); // успешный retry

        // When
        handler.consume(event);

        // Then
        // CRITICAL: placeOrder не должен вызываться, так как мы восстанавливаемся
        verify(executionPort, never()).placeOrder(any());

        // Проверка, что были попытки опроса статуса (retry)
        verify(executionPort, times(3)).getOrderStatus(clientOrderId);

        // Ордер должен перейти в финальный статус
        assertEquals(OrderStatus.FILLED, order.getStatus());

        // Lock должен быть помечен как выполненный
        verify(lockService).markExecuted(lockKey);
    }
}
