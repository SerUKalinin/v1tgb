package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ClaimVsWatchdogRaceTest {

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
    void watchdogRaceProtectionTest() throws Exception {

        // GIVEN
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId =
                IdentityFactory.deriveExecution(orderId, 1);

        Order order = Order.createPendingExecution(
                orderId,
                "CL-" + orderId,
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                "strategy-1",
                signalId
        );

        OutboxEventEntity event =
                new OutboxEventEntity();

        event.setId(UUID.randomUUID());
        event.setAggregateId(orderId);
        event.setSignalId(signalId);

        // Обязательные поля для ExecutionContext.from()
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
         * Имитируем гонку:
         * другой consumer уже забрал execution,
         * поэтому claim service ничего не возвращает.
         */
        when(orderExecutionClaimService.claim(
                any(),
                any(),
                any()
        )).thenReturn(Optional.empty());

        // WHEN
        handler.consume(event);

        // THEN
        verify(orderExecutionClaimService, times(1))
                .claim(any(), any(), any());

        // Биржа не должна вызываться.
        verify(executionPort, never())
                .placeOrder(any());

        // Order не должен сохраняться повторно.
        verify(orderRepository, never())
                .save(any());

        // Repository claim не должен вызываться напрямую
        // из OrderExecutionHandler.
        verify(orderRepository, never())
                .claimForExecution(eq(orderId), any());
    }
}