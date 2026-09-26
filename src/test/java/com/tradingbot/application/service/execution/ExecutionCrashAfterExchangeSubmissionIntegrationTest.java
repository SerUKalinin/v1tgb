package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxEventMapper;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCrashAfterExchangeSubmissionIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @SpyBean
    private ExecutionLockService executionLockService;

    @BeforeEach
    void prepareTestEnvironment() {

        when(
                systemStateManager.isReady()
        ).thenReturn(true);

        when(
                systemStateManager.isTradingEnabled()
        ).thenReturn(true);
    }

    @Test
    void crashAfterExchangeSubmissionMustNotResubmitSameExecution()
            throws Exception {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        UUID executionId =
                UUID.randomUUID();

        String clientOrderId =
                "CL-" + orderId;

        /*
         * ------------------------------------------------------------
         * 1. Реальный Order в PENDING_EXECUTION
         * ------------------------------------------------------------
         */

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
                        .status(OrderStatus.PENDING_EXECUTION)
                        .quantity(new BigDecimal("0.001"))
                        .price(new BigDecimal("60000"))
                        .executedQuantity(BigDecimal.ZERO)
                        .averagePrice(BigDecimal.ZERO)
                        .executionAttempts(0)
                        .version(0L)
                        .createdAt(Instant.now())
                        .build();

        orderRepository.saveAndFlush(
                orderEntity
        );

        /*
         * ------------------------------------------------------------
         * 2. Реальный ORDER_CREATED Outbox event
         * ------------------------------------------------------------
         */

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        UUID eventId =
                UUID.randomUUID();

        OutboxEventEntity outboxEvent =
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

        outboxEventRepository.saveAndFlush(
                outboxEvent
        );

        /*
         * ------------------------------------------------------------
         * 3. Биржа успешно принимает ордер.
         *
         * Это внешний side effect.
         * ------------------------------------------------------------
         */

        ExecutionResult exchangeResult =
                ExecutionResult.filled(
                        orderId,
                        "EXCHANGE-ORDER-1",
                        "EXCHANGE-TRADE-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        new BigDecimal("0.001"),
                        new BigDecimal("60000"),
                        BigDecimal.ZERO,
                        "USDT",
                        clientOrderId
                );

        when(
                executionPort.placeOrder(any())
        ).thenReturn(
                exchangeResult
        );

        /*
         * ------------------------------------------------------------
         * 4. Инъектируем crash внутри commit transaction.
         *
         * markExecuted() вызывается ПОСЛЕДНИМ действием
         * commit transaction.
         *
         * Исключение должно откатить:
         *   - Order mutation
         *   - completion Outbox
         *   - execution lock
         *
         * Но НЕ откатывает внешний exchange side effect.
         * ------------------------------------------------------------
         */

        doThrow(
                new IllegalStateException(
                        "SIMULATED_CRASH_AFTER_EXCHANGE_SUBMISSION"
                )
        ).when(
                executionLockService
        ).markExecuted(
                anyString()
        );

        /*
         * ------------------------------------------------------------
         * 5. Первый execution.
         *
         * Claim выполняется реально.
         * Exchange вызывается реально через mock port.
         * Commit выполняется реально.
         * Commit падает.
         * ------------------------------------------------------------
         */

        try {

            executionHandler.consume(
                    OutboxEventMapper.toDomain(
                            outboxEvent
                    )
            );

        } catch (IllegalStateException e) {

            assertThat(e)
                    .hasMessage(
                            "SIMULATED_CRASH_AFTER_EXCHANGE_SUBMISSION"
                    );
        }

        /*
         * ------------------------------------------------------------
         * 6. Проверяем DB после rollback commit transaction.
         *
         * Самая важная проверка:
         *
         * PENDING_EXECUTION -> EXECUTING
         *
         * claim transaction уже был committed ДО exchange I/O.
         *
         * Поэтому rollback commit transaction НЕ должен вернуть
         * Order обратно в PENDING_EXECUTION.
         * ------------------------------------------------------------
         */

        OrderEntity afterCrash =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertThat(afterCrash.getStatus())
                .as(
                        "Order must remain EXECUTING after commit rollback"
                )
                .isEqualTo(
                        OrderStatus.EXECUTING
                );

        assertThat(afterCrash.getExecutionId())
                .isEqualTo(executionId);

        assertThat(afterCrash.getExecutionAttempts())
                .isEqualTo(1);

        /*
         * ------------------------------------------------------------
         * 7. Completion event НЕ должен быть сохранён.
         *
         * ORDER_CREATED остаётся.
         * ORDER_EXECUTED / ORDER_COMPLETED / etc. быть не должно.
         * ------------------------------------------------------------
         */

        List<OutboxEventEntity> executionEvents =
                outboxEventRepository
                        .findAll()
                        .stream()
                        .filter(event ->
                                executionId.equals(
                                        event.getExecutionId()
                                )
                        )
                        .toList();

        assertThat(executionEvents)
                .as(
                        "Only original ORDER_CREATED must remain after commit rollback"
                )
                .hasSize(1);

        assertThat(
                executionEvents.get(0).getEventType()
        )
                .isEqualTo(
                        "ORDER_CREATED"
                );

        /*
         * ------------------------------------------------------------
         * 8. Повторная доставка ТОГО ЖЕ ORDER_CREATED.
         *
         * Критическая проверка:
         *
         * Order уже EXECUTING.
         *
         * Поэтому OrderExecutionClaimService.claim()
         * должен вернуть Optional.empty().
         *
         * Следовательно:
         *
         * executionPort.placeOrder()
         * НЕ должен быть вызван второй раз.
         * ------------------------------------------------------------
         */

        executionHandler.consume(
                OutboxEventMapper.toDomain(
                        outboxEvent
                )
        );

        /*
         * ------------------------------------------------------------
         * 9. Внешний exchange side effect произошёл ровно один раз.
         * ------------------------------------------------------------
         */

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                any()
        );

        /*
         * ------------------------------------------------------------
         * 10. DB всё ещё EXECUTING.
         *
         * Retry не должен самостоятельно повторно исполнять
         * lifecycle.
         * Дальше этим состоянием занимается recovery/reconciliation.
         * ------------------------------------------------------------
         */

        OrderEntity afterRetry =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertThat(afterRetry.getStatus())
                .isEqualTo(
                        OrderStatus.EXECUTING
                );

        assertThat(afterRetry.getExecutionId())
                .isEqualTo(
                        executionId
                );

        assertThat(afterRetry.getExecutionAttempts())
                .isEqualTo(
                        1
                );

        /*
         * Completion Outbox по-прежнему отсутствует.
         */

        List<OutboxEventEntity> completionEvents =
                outboxEventRepository
                        .findAll()
                        .stream()
                        .filter(event ->
                                executionId.equals(
                                        event.getExecutionId()
                                )
                        )
                        .filter(event ->
                                !"ORDER_CREATED".equals(
                                        event.getEventType()
                                )
                        )
                        .toList();

        assertThat(completionEvents)
                .as(
                        "Commit rollback must remove completion Outbox event"
                )
                .isEmpty();
    }
}