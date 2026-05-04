package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ExchangeTimeoutRecoveryTest {

    private OrderExecutionHandler handler;
    private ExecutionPort executionPort;
    private OrderRepository orderRepository;
    private ExecutionLockService lockService;
    private IdempotencyService idempotencyService;
    private SystemStateManager stateManager;
    private StateTransitionExecutor transitionExecutor;
    private TransitionValidator transitionValidator;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepository.class);
        lockService = mock(ExecutionLockService.class);
        idempotencyService = mock(IdempotencyService.class);
        stateManager = mock(SystemStateManager.class);
        transitionExecutor = mock(StateTransitionExecutor.class);
        transitionValidator = mock(TransitionValidator.class);

        handler = new OrderExecutionHandler(
                executionPort,
                orderRepository,
                new OrderMapper(),
                stateManager,
                idempotencyService,
                lockService,
                mock(RiskEngine.class),
                mock(OutboxService.class),
                transitionExecutor,
                transitionValidator
        );

        when(stateManager.isReady()).thenReturn(true);

        // FIX: чтобы лямбда внутри execute реально выполнялась
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(3);
            runnable.run();
            return null;
        }).when(transitionExecutor).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should recover from timeout without duplicate placement")
    void shouldRecoverFromTimeoutWithoutDuplicatePlacement() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String clientOrderId = "C-" + orderId;
        String lockKey = "EXEC_ORDER_" + orderId;

        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setId(orderId);
        orderEntity.setClientOrderId(clientOrderId);
        orderEntity.setStatus(OrderStatus.PENDING_EXECUTION);
        orderEntity.setQuantity(BigDecimal.ONE);
        orderEntity.setPrice(new BigDecimal("100"));
        orderEntity.setSide(OrderSide.BUY);
        orderEntity.setType(OrderType.MARKET);
        orderEntity.setExecutionAttempts(0);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(orderId)
                .build();

        // Моки
        when(idempotencyService.isAlreadyProcessed(eventId)).thenReturn(false);
        when(lockService.getLockState(lockKey)).thenReturn(null);
        when(lockService.tryEnterExecuting(lockKey)).thenReturn(true);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(orderEntity));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(orderEntity));

        // Симуляция timeout
        when(executionPort.placeOrder(any())).thenReturn(
                ExecutionResult.builder()
                        .status(ExecutionResult.Status.TIMEOUT)
                        .build()
        );

        // При getOrderStatus — SUCCESS
        when(executionPort.getOrderStatus(clientOrderId)).thenReturn(
                ExecutionResult.builder()
                        .status(ExecutionResult.Status.SUCCESS)
                        .exchangeOrderId("EX-123")
                        .executedQty(BigDecimal.ONE)
                        .executedPrice(new BigDecimal("100"))
                        .build()
        );

        // When: первая попытка
        handler.consume(event);

        // Then
        verify(executionPort, times(1)).placeOrder(any());
        verify(executionPort, times(1)).getOrderStatus(clientOrderId);

        // Проверяем, что статус сменился на FILLED
        ArgumentCaptor<OrderStatus> statusCaptor = ArgumentCaptor.forClass(OrderStatus.class);
        verify(transitionExecutor, atLeastOnce()).execute(any(), eq(orderEntity), statusCaptor.capture(), any());
        assertEquals(OrderStatus.FILLED, statusCaptor.getValue());

        // Проверяем, что executionAttempts увеличился
        assertEquals(1, orderEntity.getExecutionAttempts());

        // Симуляция повторной обработки (retry)
        reset(executionPort);
        when(lockService.getLockState(lockKey)).thenReturn("EXECUTED");

        handler.consume(event);

        // placeOrder не вызывается повторно
        verify(executionPort, never()).placeOrder(any());
    }
}