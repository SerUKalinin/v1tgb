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

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        stateManager = mock(SystemStateManager.class);

        handler = new OrderExecutionHandler(
                executionPort,
                orderRepository,
                new OrderMapper(),
                stateManager,
                idempotencyService,
                mock(RiskEngine.class),
                new ObjectMapper(),
                mock(OutboxEventRepository.class)
        );
    }
    @Test
    @DisplayName("Should not execute order if event is already processed")
    void duplicateEventProtectionTest() throws Exception {
        // Given
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(UUID.randomUUID())
                .build();

        when(idempotencyService.isAlreadyProcessed(eventId)).thenReturn(true);

        // When
        handler.consume(event);

        // Then
        verify(executionPort, never()).placeOrder(any());
        // Если уже обработано, мы просто выходим, не вызывая markAsProcessed повторно
        verify(idempotencyService, never()).markAsProcessed(any(UUID.class), anyString());
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

        // tryClaimOrder returns empty if status is not PENDING_EXECUTION
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        // Mock for the check in consume()
        OrderEntity alreadyExecutingOrder = OrderEntity.builder()
                .id(orderId)
                .status(OrderStatus.EXECUTING.name())
                .build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(alreadyExecutingOrder));

        // When
        handler.consume(event);

        // Then
        verify(executionPort, never()).placeOrder(any());
        // Должен пометить как обработанное, так как ордер уже в работе (статус не PENDING_EXECUTION)
        verify(idempotencyService, times(1)).markAsProcessed(eq(eventId), anyString());
    }
}
