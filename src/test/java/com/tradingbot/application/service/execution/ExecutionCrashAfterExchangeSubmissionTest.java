package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxEventMapper;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionCrashAfterExchangeSubmissionTest {

    private OrderExecutionHandler handler;

    private ExecutionPort executionPort;
    private OrderExecutionClaimService claimService;
    private OrderExecutionCommitService commitService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {

        executionPort =
                mock(ExecutionPort.class);

        claimService =
                mock(OrderExecutionClaimService.class);

        commitService =
                mock(OrderExecutionCommitService.class);

        objectMapper =
                new ObjectMapper();

        handler =
                new OrderExecutionHandler(
                        executionPort,
                        mock(OrderRepositoryPort.class),
                        claimService,
                        mock(ExecutionLockService.class),
                        mock(OrderCompensationService.class),
                        mock(OutboxService.class),
                        mock(ExecutionLogger.class),
                        objectMapper,
                        commitService
                );
    }

    @Test
    void exchangeSubmissionFollowedByCommitFailureMustNotPlaceOrderAgain()
            throws Exception {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-" + orderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "test-strategy",
                        signalId
                );

        /*
         * Создаём реальный lifecycle context:
         *
         * PENDING_EXECUTION
         *        ↓
         *    EXECUTING
         *
         * Именно в таком состоянии exchange I/O выполняется.
         */
        var context =
                com.tradingbot.tracing.ExecutionContext.of(
                        order
                );

        order.markExecuting(
                context
        );

        UUID executionId =
                order.getExecutionId();

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        UUID eventId =
                UUID.randomUUID();

        OutboxEventEntity entity =
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
                        .payload(
                                objectMapper.writeValueAsString(
                                        payload
                                )
                        )
                        .status(OutboxStatus.NEW)
                        .sequenceNumber(1L)
                        .retryCount(0)
                        .attemptCount(1)
                        .schemaVersion(1)
                        .createdAt(Instant.now())
                        .build();

        OutboxEvent event =
                OutboxEventMapper.toDomain(
                        entity
                );

        ExecutionResult exchangeResult =
                ExecutionResult.filled(
                        orderId,
                        "exchange-order-1",
                        "exchange-trade-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        BigDecimal.ONE,
                        new BigDecimal("101"),
                        BigDecimal.ZERO,
                        "USDT",
                        order.getClientOrderId()
                );

        /*
         * Первая доставка успешно получает execution ownership.
         * Вторая доставка ownership уже не получает.
         */
        when(
                claimService.claim(
                        any(),
                        any(),
                        any()
                )
        )
                .thenReturn(
                        Optional.of(order),
                        Optional.empty()
                );

        when(
                executionPort.placeOrder(
                        any()
                )
        )
                .thenReturn(
                        exchangeResult
                );

        /*
         * Имитируем crash/failure на transactional commit
         * ПОСЛЕ успешного exchange submission.
         */
        doThrow(
                new IllegalStateException(
                        "Simulated commit crash"
                )
        )
                .when(commitService)
                .commit(
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString()
                );

        /*
         * Первая доставка:
         *
         * claim -> exchange submission -> commit crash.
         *
         * Handler обязан пробросить commit exception.
         */
        assertThatThrownBy(
                () ->
                        handler.consume(
                                event
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Simulated commit crash"
                );

        /*
         * Вторая доставка того же ORDER_CREATED.
         *
         * Claim service должен вернуть empty:
         * execution уже принадлежит lifecycle.
         *
         * Поэтому exchange повторно НЕ вызывается.
         */
        handler.consume(
                event
        );

        verify(
                claimService,
                times(2)
        )
                .claim(
                        any(),
                        any(),
                        any()
                );

        verify(
                executionPort,
                times(1)
        )
                .placeOrder(
                        any()
                );

        verify(
                commitService,
                times(1)
        )
                .commit(
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString()
                );
    }
}