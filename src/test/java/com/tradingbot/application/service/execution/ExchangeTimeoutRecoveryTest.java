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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExchangeTimeoutRecoveryTest {

    private OrderExecutionHandler handler;
    private ExecutionPort executionPort;
    private OrderRepositoryPort orderRepository;
    private ExecutionLockService lockService;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepositoryPort.class);
        lockService = mock(ExecutionLockService.class);

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
                mock(TransitionValidator.class)
        );
    }

    @Test
    void recoveryFromTimeoutTest() throws Exception {
        UUID orderId = UUID.randomUUID();
        String clientOrderId = "CL-" + orderId;

        Order order = Order.builder()
                .id(orderId)
                .clientOrderId(clientOrderId)
                .status(OrderStatus.EXECUTING) // 🔥 FIX: должен быть EXECUTING
                .build();

        when(orderRepository.claimForExecution(orderId))
                .thenReturn(Optional.of(order));

        // recovery branch
        when(lockService.tryEnterExecuting(any()))
                .thenReturn(false);

        when(lockService.getLockState(any()))
                .thenReturn("PENDING");

        when(executionPort.getOrderStatus(clientOrderId))
                .thenReturn(ExecutionResult.builder()
                        .status(ExecutionResult.Status.SUCCESS)
                        .exchangeOrderId("EX-123")
                        .executedQty(BigDecimal.ONE)
                        .executedPrice(new BigDecimal("50000"))
                        .build());

        handler.consume(
                OutboxEventEntity.builder()
                        .aggregateId(orderId)
                        .build()
        );

        verify(executionPort, never()).placeOrder(any());
        verify(lockService).markExecuted(any());
    }
}