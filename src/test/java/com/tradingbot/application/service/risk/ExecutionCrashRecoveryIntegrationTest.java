package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.execution.OrderExecutionHandler;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.execution.ExecutionLockEntity;
import com.tradingbot.infrastructure.execution.ExecutionLockRepository;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.outbox.OutboxEventMapper;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F1.2 — Recovery after crash following exchange submission.
 *
 * Контракты:
 *
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 *
 * Проверяем сценарий:
 *
 * ORDER_CREATED
 *      ↓
 * PENDING_EXECUTION
 *      ↓
 * atomic claim
 *      ↓
 * EXECUTING
 *      ↓
 * exchange.placeOrder()
 *      ↓
 * exchange side effect completed
 *      ↓
 * execution commit starts
 *      ↓
 * simulated crash
 *      ↓
 * commit transaction rollback
 *      ↓
 * Order = EXECUTING
 * Lock  = EXECUTING
 *      ↓
 * execution becomes stale
 *      ↓
 * reconciliation claim
 *      ↓
 * exchange query
 *      ↓
 * exchange = FILLED
 *      ↓
 * Order = FILLED
 *
 * Критический инвариант:
 *
 * exchange.placeOrder() вызывается ровно один раз.
 *
 * Recovery НИКОГДА не использует повторный submit path.
 */
class ExecutionCrashRecoveryIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler orderExecutionHandler;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private OrderRepositoryPort orderRepositoryPort;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ExecutionLockRepository executionLockRepository;

    @Autowired
    private ExecutionClaimRepository executionClaimRepository;

    @Autowired
    private SystemStateManager systemStateManager;

    @SpyBean
    private ExecutionLockService executionLockService;

    /**
     * Реальный execution submit path.
     *
     * Здесь должен существовать только placeOrder().
     */
    @MockBean
    private ExecutionPort executionPort;

    /**
     * Recovery/reconciliation path.
     *
     * Recovery должен спрашивать authoritative exchange state,
     * а НЕ повторно отправлять ордер через ExecutionPort.
     */
    @MockBean
    private ExchangeOrderQueryService exchangeOrderQueryService;

    /**
     * F1.2 не проверяет Risk settlement.
     */
    @MockBean
    private OrderCompensationService orderCompensationService;

    @BeforeEach
    void prepareTestEnvironment() {

        /*
         * Spring bootstrap может перевести систему в HALTED
         * из-за отсутствия реального exchange balance в test profile.
         *
         * Для самого F1.2 system state должен быть READY.
         */
        systemStateManager.updateState(
                SystemStateManager.SystemState.READY
        );
    }

    @Test
    @DisplayName(
            "F1.2: crash after exchange submission must be recovered without resubmission"
    )
    void crashAfterExchangeSubmissionMustBeRecoveredWithoutResubmission()
            throws Exception {

        // ============================================================
        // GIVEN — IDENTITIES
        // ============================================================

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        String clientOrderId =
                "f12-" + orderId;

        // ============================================================
        // GIVEN — ORDER
        // ============================================================

        Order order =
                Order.createPendingExecution(
                        orderId,
                        clientOrderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.001"),
                        new BigDecimal("60000"),
                        "F1.2-RECOVERY",
                        signalId
                );

        UUID executionId =
                order.getExecutionId();

        assertNotNull(
                executionId,
                "executionId must be created by Order.createPendingExecution()"
        );

        OrderEntity orderEntity =
                orderMapper.toEntity(order);

        orderRepository.saveAndFlush(
                orderEntity
        );

        // ============================================================
        // GIVEN — SOURCE ORDER_CREATED OUTBOX EVENT
        // ============================================================

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        orderId,
                        executionId
                );

        UUID eventId =
                UUID.randomUUID();

        OutboxEventEntity outboxEventEntity =
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
                outboxEventEntity
        );

        OutboxEvent orderCreatedEvent =
                OutboxEventMapper.toDomain(
                        outboxEventEntity
                );

        // ============================================================
        // GIVEN — EXECUTION CONTEXT
        // ============================================================

        ExecutionContext context =
                ExecutionContext.of(order);

        assertEquals(
                executionId,
                context.attempt().executionId(),
                "ExecutionContext must carry canonical executionId"
        );

        // ============================================================
        // GIVEN — AUTHORITATIVE EXCHANGE RESULT
        // ============================================================

        ExecutionResult filledResult =
                ExecutionResult.filled(
                        orderId,
                        "exchange-order-1",
                        "exchange-trade-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        new BigDecimal("0.001"),
                        new BigDecimal("60000"),
                        BigDecimal.ZERO,
                        "USDT",
                        clientOrderId
                );

        /*
         * Initial execution:
         * ExecutionPort.placeOrder()
         */
        when(
                executionPort.placeOrder(
                        any(Order.class)
                )
        ).thenReturn(
                filledResult
        );

        /*
         * Recovery:
         * ExchangeOrderQueryService.getOrderStatus()
         */
        when(
                exchangeOrderQueryService.getOrderStatus(
                        eq("BTCUSDT"),
                        eq(clientOrderId)
                )
        ).thenReturn(
                filledResult
        );

        // ============================================================
        // GIVEN — CRASH AFTER EXCHANGE SUBMISSION
        // ============================================================

        AtomicBoolean crashInjected =
                new AtomicBoolean(false);

        doAnswer(invocation -> {

            boolean updated =
                    (boolean) invocation.callRealMethod();

            assertTrue(
                    updated,
                    "Execution lock must transition EXECUTING -> EXECUTED before simulated crash"
            );

            if (crashInjected.compareAndSet(false, true)) {

                throw new IllegalStateException(
                        "SIMULATED_CRASH_AFTER_EXCHANGE_SUBMISSION"
                );
            }

            return updated;

        }).when(executionLockService)
                .markExecuted(
                        anyString()
                );

        // ============================================================
        // WHEN — FIRST EXECUTION
        // ============================================================

        IllegalStateException crash =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                orderExecutionHandler.consume(
                                        orderCreatedEvent
                                )
                );

        assertEquals(
                "SIMULATED_CRASH_AFTER_EXCHANGE_SUBMISSION",
                crash.getMessage()
        );

        // ============================================================
        // THEN — EXCHANGE SUBMIT EXACTLY ONCE
        // ============================================================

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                any(Order.class)
        );

        // ============================================================
        // THEN — COMMIT ROLLBACK DID NOT ROLLBACK CLAIM
        // ============================================================

        OrderEntity afterCrash =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.EXECUTING,
                afterCrash.getStatus(),
                "Claim transaction must stay committed as EXECUTING"
        );

        assertEquals(
                executionId,
                afterCrash.getExecutionId(),
                "executionId must remain immutable"
        );

        assertEquals(
                1,
                afterCrash.getExecutionAttempts(),
                "Execution attempt must be incremented exactly once"
        );

        assertFalse(
                outboxEventRepository
                        .findAll()
                        .stream()
                        .anyMatch(event ->
                                executionId.equals(
                                        event.getExecutionId()
                                )
                                        && !"ORDER_CREATED".equals(
                                        event.getEventType()
                                )
                        ),
                "Completion outbox event must rollback"
        );

        // ============================================================
        // THEN — EXECUTION LOCK ROLLED BACK
        // ============================================================

        String lockKey =
                "EXEC_ORDER_" + orderId;

        assertEquals(
                "EXECUTING",
                executionLockService.getLockState(lockKey),
                "Execution lock must remain EXECUTING after commit rollback"
        );

        assertEquals(
                "EXECUTING",
                executionLockRepository
                        .findById(lockKey)
                        .orElseThrow()
                        .getState()
        );

        // ============================================================
        // THEN — EXECUTION CLAIM EXISTS
        // ============================================================

        assertTrue(
                executionClaimRepository
                        .existsByExecutionId(executionId),
                "Execution claim must remain persisted"
        );

        // ============================================================
        // GIVEN — RECOVERY WINDOW IS OPEN
        // ============================================================

        /*
         * Fresh EXECUTING order is intentionally protected from
         * reconciliation.
         *
         * Production stale threshold = 30 seconds.
         *
         * Не делаем Thread.sleep().
         * Просто переводим executionStartedAt в прошлое.
         */
        OrderEntity staleExecutionEntity =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        Instant recoveryEligibleSince =
                Instant.now()
                        .minusSeconds(60);

        staleExecutionEntity.setExecutionStartedAt(
                recoveryEligibleSince
        );

        orderRepository.saveAndFlush(
                staleExecutionEntity
        );

        Order staleOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.EXECUTING,
                staleOrder.getStatus(),
                "Order must still be EXECUTING before reconciliation"
        );

        OrderEntity persistedStaleEntity =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertTrue(
                persistedStaleEntity
                        .getExecutionStartedAt()
                        .isBefore(
                                Instant.now()
                                        .minusSeconds(30)
                        ),
                "Order must be stale enough for reconciliation"
        );

        assertEquals(
                executionId,
                staleOrder.getExecutionId(),
                "Recovery must use the same execution lifecycle identity"
        );

        // ============================================================
        // WHEN — RECONCILIATION
        // ============================================================

        reconciliationService.reconcile(
                context
        );

        // ============================================================
        // THEN — EXCHANGE STATE WAS QUERIED
        // ============================================================

        verify(
                exchangeOrderQueryService,
                times(1)
        ).getOrderStatus(
                eq("BTCUSDT"),
                eq(clientOrderId)
        );

        // ============================================================
        // THEN — EXCHANGE SUBMIT WAS NOT REPEATED
        // ============================================================

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                any(Order.class)
        );

        // ============================================================
        // THEN — ORDER IS FINALLY FILLED
        // ============================================================

        OrderEntity recoveredOrderEntity =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.FILLED,
                recoveredOrderEntity.getStatus(),
                "Authoritative exchange FILLED state must complete recovery"
        );

        assertEquals(
                executionId,
                recoveredOrderEntity.getExecutionId(),
                "Recovery must preserve the same executionId"
        );

        /*
         * BigDecimal.equals() учитывает scale.
         *
         * DB может вернуть:
         *
         *     0.001000000000000000
         *
         * вместо:
         *
         *     0.001
         *
         * Числовое значение при этом одинаковое.
         */
        assertBigDecimalValue(
                new BigDecimal("0.001"),
                recoveredOrderEntity.getExecutedQuantity(),
                "Executed quantity"
        );

        assertBigDecimalValue(
                new BigDecimal("60000"),
                recoveredOrderEntity.getAveragePrice(),
                "Average price"
        );

        assertEquals(
                "exchange-order-1",
                recoveredOrderEntity.getExchangeOrderId()
        );

        // ============================================================
        // WHEN — SECOND RECOVERY ATTEMPT
        // ============================================================

        reconciliationService.reconcile(
                context
        );

        // ============================================================
        // THEN — NO SECOND EXCHANGE SUBMISSION
        // ============================================================

        verify(
                executionPort,
                times(1)
        ).placeOrder(
                any(Order.class)
        );

        /*
         * Terminal lifecycle must remain FILLED.
         */
        OrderEntity terminalOrder =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.FILLED,
                terminalOrder.getStatus()
        );

        assertEquals(
                executionId,
                terminalOrder.getExecutionId()
        );
    }

    /**
     * Сравнение BigDecimal по числовому значению,
     * без зависимости от scale.
     */
    private static void assertBigDecimalValue(
            BigDecimal expected,
            BigDecimal actual,
            String fieldName
    ) {
        assertNotNull(
                actual,
                fieldName + " must not be null"
        );

        assertEquals(
                0,
                expected.compareTo(actual),
                fieldName
                        + " must be numerically equal. " +
                        "expected="
                        + expected.toPlainString()
                        + ", actual="
                        + actual.toPlainString()
        );
    }
}