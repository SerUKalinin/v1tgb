package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class OrderExecutionIdempotencyTest {

    private OrderExecutionHandler handler;
    private OrderRepositoryPort orderRepository;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepositoryPort.class);
        idempotencyService = mock(IdempotencyService.class);

        handler = new OrderExecutionHandler(
                mock(ExecutionPort.class),
                orderRepository,
                mock(SystemStateManager.class),
                idempotencyService,
                mock(ExecutionLockService.class),
                mock(RiskEngine.class),
                mock(OutboxService.class),
                mock(TransitionValidator.class)
        );
    }

    @Test
    void shouldSkipIfAlreadyProcessedByIdempotencyKey() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        when(idempotencyService.isAlreadyProcessed(eventId)).thenReturn(true);

        handler.consume(OutboxEventEntity.builder().id(eventId).aggregateId(orderId).build());

        verify(orderRepository, never()).claimForExecution(any());
    }
}
