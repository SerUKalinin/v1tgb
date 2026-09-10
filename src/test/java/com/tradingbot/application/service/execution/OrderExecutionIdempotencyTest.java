package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderExecutionIdempotencyTest {

    private OrderExecutionHandler handler;
    private OrderRepositoryPort orderRepository;
    private ExecutionClaimPort executionClaimPort;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepositoryPort.class);
        executionClaimPort = mock(ExecutionClaimPort.class);
        objectMapper = new ObjectMapper();

        SystemStateManager stateManager = mock(SystemStateManager.class);
        when(stateManager.isReady()).thenReturn(true);

        handler = new OrderExecutionHandler(
                mock(ExecutionPort.class),
                orderRepository,
                stateManager,
                mock(ExecutionLockService.class),
                mock(OrderCompensationService.class),
                mock(OutboxService.class),
                mock(TransitionValidator.class),
                mock(ExecutionLogger.class),
                executionClaimPort,
                objectMapper
        );
    }

    @Test
    void shouldSkipIfAlreadyClaimedByExecutionId() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        OrderCreatedEvent payload = new OrderCreatedEvent(signalId, orderId, executionId);
        ExecutionContext mockCtx = mock(ExecutionContext.class);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .eventId(eventId)
                .aggregateId(orderId)
                .signalId(signalId)
                .orderId(orderId)
                .executionId(executionId)
                .causationId(orderId)
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload(objectMapper.writeValueAsString(payload))
                .build();

        // Execution уже заклеймлен — защита от повторной отправки
        when(executionClaimPort.existsByExecutionId(executionId)).thenReturn(true);

        handler.consume(event);

        // Ни исполнения, ни сохранения
        verify(orderRepository, never()).claimForExecution(any(), any());
        verify(orderRepository, never()).save(any());
        verify(executionClaimPort, never()).claimExecution(any(), any());
    }
}
