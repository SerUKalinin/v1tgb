package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxEventMapper;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExchangeTimeoutRecoveryTest {

    private OrderExecutionHandler handler;

    private ExecutionPort executionPort;
    private OrderRepositoryPort orderRepository;
    private OrderExecutionClaimService orderExecutionClaimService;
    private OrderExecutionCommitService orderExecutionCommitService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {

        executionPort =
                mock(ExecutionPort.class);

        orderRepository =
                mock(OrderRepositoryPort.class);

        orderExecutionClaimService =
                mock(OrderExecutionClaimService.class);

        orderExecutionCommitService =
                mock(OrderExecutionCommitService.class);

        objectMapper =
                new ObjectMapper();

        handler =
                new OrderExecutionHandler(
                        executionPort,
                        orderRepository,
                        orderExecutionClaimService,
                        mock(ExecutionLockService.class),
                        mock(OrderCompensationService.class),
                        mock(OutboxService.class),
                        mock(ExecutionLogger.class),
                        objectMapper,
                        orderExecutionCommitService
                );
    }

    @Test
    void exchangeTimeoutMustNotCommitAndMustNotPlaceDuplicateOrder()
            throws Exception {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        UUID executionId =
                IdentityFactory.deriveExecution(
                        orderId,
                        1
                );

        UUID eventId =
                UUID.randomUUID();

        String clientOrderId =
                "CL-" + orderId;

        Order order =
                Order.createPendingExecution(
                        orderId,
                        clientOrderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("50000"),
                        "strategy-1",
                        signalId
                );

        ReflectionTestUtils.setField(
                order,
                "executionId",
                executionId
        );

        ReflectionTestUtils.setField(
                order,
                "executionAttempts",
                1
        );

        ReflectionTestUtils.setField(
                order,
                "executionStartedAt",
                Instant.now()
        );

        ReflectionTestUtils.setField(
                order,
                "status",
                OrderStatus.EXECUTING
        );

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        OutboxEventEntity event =
                OutboxEventEntity.builder()
                        .id(eventId)
                        .eventId(eventId)
                        .aggregateId(orderId)
                        .signalId(signalId)
                        .orderId(orderId)
                        .executionId(executionId)
                        .causationId(signalId)
                        .correlationId(signalId)
                        .aggregateType("ORDER")
                        .eventType("ORDER_CREATED")
                        .attemptCount(1)
                        .payload(
                                objectMapper.writeValueAsString(
                                        payload
                                )
                        )
                        .build();

        when(
                orderExecutionClaimService.claim(
                        any(),
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.of(order)
        );

        when(
                executionPort.placeOrder(
                        eq(order)
                )
        ).thenThrow(
                new RuntimeException(
                        "EXCHANGE_TIMEOUT"
                )
        );

        handler.consume(
                OutboxEventMapper.toDomain(event)
        );

        verify(
                orderExecutionClaimService,
                times(1)
        ).claim(
                any(),
                any(),
                any()
        );

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                eq(order)
        );

        verify(
                orderExecutionCommitService,
                never()
        ).commit(
                any(),
                any(),
                any(),
                any(),
                anyString()
        );

        assertEquals(
                OrderStatus.EXECUTING,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        assertEquals(
                1,
                order.getExecutionAttempts()
        );

        assertNotNull(
                order.getExecutionStartedAt()
        );

        /*
         * SECOND PASS
         */
        when(
                orderExecutionClaimService.claim(
                        any(),
                        any(),
                        any()
                )
        ).thenReturn(
                Optional.empty()
        );

        handler.consume(
                OutboxEventMapper.toDomain(event)
        );

        verify(
                orderExecutionClaimService,
                times(2)
        ).claim(
                any(),
                any(),
                any()
        );

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                eq(order)
        );

        verify(
                orderExecutionCommitService,
                never()
        ).commit(
                any(),
                any(),
                any(),
                any(),
                anyString()
        );

        verify(
                orderRepository,
                never()
        ).save(
                any()
        );
    }
}