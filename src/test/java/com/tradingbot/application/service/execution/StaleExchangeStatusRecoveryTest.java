package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
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

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class StaleExchangeStatusRecoveryTest {

    private OrderExecutionHandler handler;
    private ExecutionPort executionPort;
    private OrderRepository orderRepository;
    private ExecutionLockService lockService;
    private IdempotencyService idempotencyService;
    private TransitionValidator transitionValidator;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepository.class);
        lockService = mock(ExecutionLockService.class);
        idempotencyService = mock(IdempotencyService.class);
        transitionValidator = mock(TransitionValidator.class);

        SystemStateManager stateManager = mock(SystemStateManager.class);
        when(stateManager.isReady()).thenReturn(true);

        // Мок transitionExecutor чтобы статус ордера автоматически ставился FILLED
        StateTransitionExecutor transitionExecutor = mock(StateTransitionExecutor.class);
        doAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(1);
            order.setStatus(OrderStatus.FILLED); // имитируем commit
            return null;
        }).when(transitionExecutor).execute(any(), any(), any(), any());

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
    }

    @Test
    @DisplayName("System MUST NOT duplicate order if exchange status is temporarily stale (TIMEOUT/NOT_FOUND)")
    void shouldRecoverFromStaleExchangeStatusWithoutDuplicatePlacement() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String clientOrderId = "CL-" + orderId;

        OrderEntity entity = new OrderEntity();
        entity.setId(orderId);
        entity.setClientOrderId(clientOrderId);
        entity.setStatus(OrderStatus.PENDING_EXECUTION);
        entity.setSymbol("BTCUSDT");
        entity.setQuantity(BigDecimal.ONE);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(orderId)
                .build();

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(entity));
        when(orderRepository.saveAndFlush(any())).thenReturn(entity);
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
        verify(executionPort, never()).placeOrder(any()); // CRITICAL: placeOrder не вызывается
        verify(executionPort, times(3)).getOrderStatus(clientOrderId); // проверка retry

        assertEquals(OrderStatus.FILLED, entity.getStatus()); // ордер помечен как FILLED
        verify(lockService).markExecuted(lockKey); // lock помечен как выполненный
    }
}