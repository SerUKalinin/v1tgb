package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExchangeTimeoutRecoveryTest {

    private OrderExecutionHandler handler;
    private ExecutionPort executionPort;
    private OrderRepositoryPort orderRepository;
    private OrderExecutionClaimService orderExecutionClaimService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        executionPort = mock(ExecutionPort.class);
        orderRepository = mock(OrderRepositoryPort.class);
        orderExecutionClaimService = mock(OrderExecutionClaimService.class);
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
    void recoveryFromTimeoutTest() throws Exception {

        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = IdentityFactory.deriveExecution(orderId, 1);
        String clientOrderId = "CL-" + orderId;

        Order order = Order.createPendingExecution(
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

        OutboxEventEntity event = new OutboxEventEntity();

        event.setId(UUID.randomUUID());
        event.setAggregateId(orderId);
        event.setSignalId(signalId);
        event.setExecutionId(executionId);
        event.setCausationId(orderId);
        event.setCorrelationId(signalId);
        event.setAttemptCount(1);

        event.setPayload(
                objectMapper.writeValueAsString(
                        new OrderCreatedEvent(
                                signalId,
                                orderId,
                                executionId
                        )
                )
        );

        /*
         * Claim уже выполнен.
         * Для этого unit-теста просто передаём готовый Order
         * дальше в execution pipeline.
         */
        when(orderExecutionClaimService.claim(
                any(),
                any(),
                any()
        )).thenReturn(Optional.of(order));

        when(executionPort.placeOrder(eq(order)))
                .thenReturn(
                        ExecutionResult.filled(
                                orderId,
                                "EX-123",
                                "trade-123",
                                "BTCUSDT",
                                OrderSide.BUY,
                                BigDecimal.ONE,
                                new BigDecimal("50000"),
                                BigDecimal.ZERO,
                                "USDT",
                                clientOrderId
                        )
                );

        handler.consume(event);

        verify(orderExecutionClaimService, times(1))
                .claim(any(), any(), any());

        verify(executionPort, times(1))
                .placeOrder(eq(order));
    }
}