package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.outbox.OutboxEventMapper;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCrashPersistenceRecoveryTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @MockBean
    private OrderExecutionCommitService orderExecutionCommitService;

    @BeforeEach
    void setUpSystemState() {

        when(
                systemStateManager.isReady()
        ).thenReturn(true);
    }

    @Test
    void commitCrashMustLeavePersistedExecutingOwnership()
            throws Exception {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        /*
         * Canonical execution identity:
         *
         * orderId
         *    ↓
         * executionId
         */
        UUID executionId =
                IdentityFactory.deriveExecution(
                        orderId,
                        1
                );

        String clientOrderId =
                "CL-" + orderId;

        OrderEntity orderEntity =
                OrderEntity.builder()
                        .id(orderId)
                        .clientOrderId(clientOrderId)
                        .signalId(signalId)
                        .executionId(executionId)
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .strategyId("test-strategy")
                        .status(
                                OrderStatus.PENDING_EXECUTION
                        )
                        .quantity(BigDecimal.ONE)
                        .price(
                                BigDecimal.valueOf(50000)
                        )
                        .version(0L)
                        .executionAttempts(0)
                        .executionStartedAt(null)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        orderRepository.saveAndFlush(
                orderEntity
        );

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        UUID eventId =
                UUID.randomUUID();

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
                        .updatedAt(Instant.now())
                        .build();

        outboxRepository.saveAndFlush(
                event
        );

        ExecutionResult exchangeResult =
                ExecutionResult.filled(
                        orderId,
                        "exchange-order-1",
                        "exchange-trade-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        BigDecimal.ONE,
                        new BigDecimal("50100"),
                        BigDecimal.ZERO,
                        "USDT",
                        clientOrderId
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
         * Симулируем crash непосредственно после
         * exchange submission:
         *
         * PENDING_EXECUTION
         *        ↓
         * EXECUTING persisted
         *        ↓
         * exchange accepted
         *        ↓
         * commit crashes
         *
         * commit() возвращает void, поэтому используется
         * doThrow(...).when(...).
         */
        doThrow(
                new IllegalStateException(
                        "Simulated commit crash"
                )
        )
                .when(
                        orderExecutionCommitService
                )
                .commit(
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        var domainEvent =
                OutboxEventMapper.toDomain(
                        event
                );

        assertThatThrownBy(
                () ->
                        executionHandler.consume(
                                domainEvent
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Simulated commit crash"
                );

        /*
         * КРИТИЧЕСКАЯ ПРОВЕРКА:
         *
         * claim transaction уже закоммитилась ДО exchange I/O.
         *
         * Поэтому crash commit НЕ должен откатить:
         *
         *     PENDING_EXECUTION -> EXECUTING
         */
        OrderEntity afterCrash =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.EXECUTING,
                afterCrash.getStatus(),
                "claim ownership must remain persisted after commit crash"
        );

        assertEquals(
                1,
                afterCrash.getExecutionAttempts(),
                "executionAttempts must be incremented exactly once"
        );

        assertEquals(
                executionId,
                afterCrash.getExecutionId(),
                "executionId must remain immutable"
        );

        /*
         * Повторная доставка того же ORDER_CREATED.
         *
         * Реальный OrderExecutionClaimService +
         * OrderRepositoryAdapter должны увидеть:
         *
         *     EXECUTING
         *
         * и не выдать execution ownership повторно.
         *
         * Следовательно:
         *
         *     placeOrder() не вызывается второй раз.
         */
        executionHandler.consume(
                domainEvent
        );

        verify(
                executionPort,
                times(1)
        )
                .placeOrder(
                        any()
                );

        verify(
                orderExecutionCommitService,
                times(1)
        )
                .commit(
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        /*
         * Состояние не должно откатиться
         * и новая execution attempt не должна появиться.
         */
        OrderEntity finalState =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.EXECUTING,
                finalState.getStatus()
        );

        assertEquals(
                1,
                finalState.getExecutionAttempts()
        );

        assertEquals(
                executionId,
                finalState.getExecutionId()
        );
    }
}