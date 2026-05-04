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
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClaimVsWatchdogRaceTest {

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

        handler = new OrderExecutionHandler(
                executionPort,
                orderRepository,
                new OrderMapper(),
                stateManager,
                idempotencyService,
                lockService,
                mock(RiskEngine.class),
                mock(OutboxService.class),
                mock(StateTransitionExecutor.class),
                transitionValidator
        );
    }

    @Test
    @DisplayName("Watchdog MUST NOT call placeOrder if Node A already started execution")
    void watchdogRaceProtectionTest() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String clientOrderId = "CL-" + orderId;

        OrderEntity entity = new OrderEntity();
        entity.setId(orderId);
        entity.setClientOrderId(clientOrderId);
        entity.setStatus(OrderStatus.EXECUTING); // Node A уже перевела в EXECUTING
        entity.setExecutionId(UUID.randomUUID());

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(orderId)
                .build();

        // 1. Watchdog (Node B) находит ордер
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(entity));
        
        // 2. Валидатор считает ордер "зависшим" (stale)
        when(transitionValidator.isTerminal(any())).thenReturn(false);
        when(transitionValidator.isStale(eq(OrderStatus.EXECUTING), any())).thenReturn(true);

        // 3. LockService говорит, что кто-то уже выполняет (isNewExecution = false)
        String lockKey = "EXEC_ORDER_" + orderId;
        when(lockService.getLockState(lockKey)).thenReturn("PENDING");
        when(lockService.tryEnterExecuting(lockKey)).thenReturn(false); 

        // 4. Биржа подтверждает, что ордер исполнен (или просто существует)
        when(executionPort.getOrderStatus(clientOrderId)).thenReturn(
                ExecutionResult.builder().status(ExecutionResult.Status.SUCCESS).build()
        );

        // When
        handler.consume(event);

        // Then
        // CRITICAL: Node B (watchdog) НЕ должен вызывать placeOrder
        verify(executionPort, never()).placeOrder(any());
        
        // Node B должен вызвать getOrderStatus для восстановления состояния
        verify(executionPort, times(1)).getOrderStatus(clientOrderId);
    }
}
