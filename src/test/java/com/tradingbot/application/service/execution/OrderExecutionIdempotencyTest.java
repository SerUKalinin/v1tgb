package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderExecutionIdempotencyTest {

    private OrderExecutionHandler handler;
    private IdempotencyService idempotencyService;
    private OrderRepository orderRepository;
    private ExecutionPort executionPort;
    private SystemStateManager stateManager;
    private com.tradingbot.infrastructure.execution.ExecutionLockService lockService;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        stateManager = mock(SystemStateManager.class);
        lockService = mock(com.tradingbot.infrastructure.execution.ExecutionLockService.class);

        handler = new OrderExecutionHandler(
                executionPort,
                orderRepository,
                new OrderMapper(),
                stateManager,
                idempotencyService,
                lockService,
                mock(com.tradingbot.domain.risk.RiskEngine.class),
                mock(com.tradingbot.infrastructure.outbox.OutboxService.class),
                mock(com.tradingbot.application.service.execution.StateTransitionExecutor.class),
                mock(com.tradingbot.domain.policy.TransitionValidator.class)
        );
    }

    @Test
    @DisplayName("Should not execute if order is already in EXECUTING status (claimed by another thread)")
    void concurrentClaimProtectionTest() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(orderId)
                .build();

        when(idempotencyService.isAlreadyProcessed(eventId)).thenReturn(false);
        when(stateManager.isReady()).thenReturn(true);

        // 2. Проверка состояния блокировки
        String lockKey = "EXEC_ORDER_" + orderId;
        when(lockService.getLockState(lockKey)).thenReturn("EXECUTED");

        // When
        handler.consume(event);

        // Then
        verify(executionPort, never()).placeOrder(any());
        // Должен пометить как обработанное, так как ордер уже EXECUTED в локе
        verify(idempotencyService, times(1)).markAsProcessed(eq(eventId), anyString());
    }
}
