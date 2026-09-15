package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderExecutionIdempotencyTest {

    private OrderExecutionHandler handler;
    private OrderRepositoryPort orderRepository;
    private OrderExecutionClaimService orderExecutionClaimService;
    private ExecutionPort executionPort;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepositoryPort.class);
        orderExecutionClaimService = mock(OrderExecutionClaimService.class);
        executionPort = mock(ExecutionPort.class);
        objectMapper = new ObjectMapper();

        handler = new OrderExecutionHandler(
                executionPort,
                orderRepository,
                orderExecutionClaimService,
                mock(ExecutionLockService.class),
                mock(OrderCompensationService.class),
                mock(OutboxService.class),
                mock(ExecutionLogger.class),
                objectMapper
        );
    }

    @Test
    void shouldSkipIfAlreadyClaimedByExecutionId() throws Exception {

        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        ExecutionContext mockCtx =
                mock(ExecutionContext.class);

        OutboxEventEntity event =
                OutboxEventEntity.builder()
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

        /*
         * Claim service сообщает, что execution уже был обработан
         * и Order нельзя отдавать на повторное исполнение.
         */
        when(orderExecutionClaimService.claim(
                any(),
                any(),
                any()
        )).thenReturn(Optional.empty());

        handler.consume(event);

        verify(orderExecutionClaimService, times(1))
                .claim(any(), any(), any());

        verify(executionPort, never())
                .placeOrder(any());

        verify(orderRepository, never())
                .claimForExecution(any(), any());

        verify(orderRepository, never())
                .save(any());
    }
}