package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StaleExchangeStatusRecoveryTest {

    private OrderExecutionHandler handler;

    private ExecutionPort executionPort;
    private OrderRepositoryPort orderRepository;
    private ExecutionClaimPort executionClaimPort;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepositoryPort.class);
        executionClaimPort = mock(ExecutionClaimPort.class);
        objectMapper = new ObjectMapper();

        SystemStateManager stateManager = mock(SystemStateManager.class);
        when(stateManager.isReady()).thenReturn(true);

        handler = new OrderExecutionHandler(
                executionPort,
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
    @DisplayName("Recovery MUST NOT place order again — execution already claimed")
    void shouldRecoverFromStaleExchangeStatusWithoutDuplicatePlacement() throws Exception {

        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        OrderCreatedEvent payload = new OrderCreatedEvent(signalId, orderId, executionId);

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

        // ExecutionId уже заклеймлен — идемпотентный guard в claimOrder
        when(executionClaimPort.existsByExecutionId(executionId)).thenReturn(true);

        handler.consume(event);

        // КЛЮЧЕВОЕ: placeOrder НЕ вызывается
        verify(executionPort, never()).placeOrder(any());

        // Ни claim-а, ни save-а — полностью пропущено
        verify(orderRepository, never()).claimForExecution(any(), any());
        verify(orderRepository, never()).save(any());
        verify(executionClaimPort, never()).claimExecution(any(), any());
    }
}
