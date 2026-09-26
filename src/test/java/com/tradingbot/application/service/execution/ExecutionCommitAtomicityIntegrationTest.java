package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.execution.ExecutionLockEntity;
import com.tradingbot.infrastructure.execution.ExecutionLockRepository;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * F1 — Execution Commit Atomicity.
 *
 * Контракты:
 *
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 *
 * Проверяем transactional execution commit boundary:
 *
 * exchange I/O
 *      ↓
 * commit(REQUIRES_NEW)
 *      ├─ Order mutation
 *      ├─ Order save
 *      ├─ completion Outbox insert
 *      └─ ExecutionLock EXECUTING -> EXECUTED
 *              ↓
 *          COMMIT
 *
 * При exception после выполнения всех трёх DB mutations
 * transaction должна полностью откатиться.
 *
 * Ожидаемый результат:
 *
 * Order       -> EXECUTING
 * Outbox      -> completion event отсутствует
 * Lock        -> EXECUTING
 * executionId -> unchanged
 */
class ExecutionCommitAtomicityIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionCommitService commitService;

    @Autowired
    private OrderRepositoryPort orderRepositoryPort;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    /**
     * Spy нужен для fault injection ПОСЛЕ реального
     * update execution_lock -> EXECUTED.
     *
     * Это позволяет проверить rollback всей REQUIRES_NEW transaction.
     */
    @SpyBean
    private ExecutionLockService executionLockService;

    @Autowired
    private ExecutionLockRepository executionLockRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxService outboxService;

    /**
     * Risk settlement не является предметом F1.
     *
     * Commit должен дойти до compensation call,
     * но сам risk persistence здесь отключён.
     */
    @MockBean
    private OrderCompensationService orderCompensationService;

    @Test
    @DisplayName(
            "F1: execution commit must atomically rollback Order, Outbox and ExecutionLock"
    )
    void shouldRollbackEntireExecutionCommitTransaction() {

        // ============================================================
        // GIVEN — CREATE ORDER
        // ============================================================

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "f1-" + orderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.001"),
                        new BigDecimal("100"),
                        "F1-TEST",
                        signalId
                );

        UUID executionId =
                order.getExecutionId();

        assertNotNull(
                executionId,
                "executionId must be created by Order.createPendingExecution()"
        );

        OrderEntity entity =
                orderMapper.toEntity(order);

        orderRepository.saveAndFlush(entity);

        // ============================================================
        // GIVEN — CLAIM ORDER
        // PENDING_EXECUTION -> EXECUTING
        // ============================================================

        ExecutionContext context =
                ExecutionContext.of(order);

        Order claimedOrder =
                orderRepositoryPort
                        .claimForExecution(
                                orderId,
                                context
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order must be claimable for execution"
                                )
                        );

        assertEquals(
                OrderStatus.EXECUTING,
                claimedOrder.getStatus()
        );

        assertEquals(
                executionId,
                claimedOrder.getExecutionId()
        );

        // ============================================================
        // GIVEN — EXECUTION LOCK
        // ============================================================

        String lockKey =
                "EXEC_ORDER_" + orderId;

        /*
         * Не используем ExecutionLockService.tryClaim():
         *
         * production repository использует PostgreSQL-specific
         * ON CONFLICT, а test profile работает на H2.
         *
         * Поэтому fixture создаётся напрямую через JPA.
         */
        ExecutionLockEntity lockEntity =
                ExecutionLockEntity.builder()
                        .idempotencyKey(lockKey)
                        .state("CLAIMED")
                        .createdAt(Instant.now())
                        .build();

        executionLockRepository.saveAndFlush(
                lockEntity
        );

        assertEquals(
                "CLAIMED",
                executionLockService.getLockState(lockKey),
                "Execution lock must be persisted in CLAIMED state"
        );

        assertEquals(
                true,
                executionLockService.tryEnterExecuting(lockKey),
                "Execution lock must enter EXECUTING"
        );

        assertEquals(
                "EXECUTING",
                executionLockService.getLockState(lockKey)
        );

        // ============================================================
        // GIVEN — EXCHANGE RESULT
        // ============================================================

        /*
         * Exchange I/O уже считается завершённым.
         *
         * commit() получает authoritative execution result.
         */
        ExecutionResult result =
                ExecutionResult.filled(
                        orderId,
                        "exchange-order-1",
                        "exchange-trade-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        new BigDecimal("0.001"),
                        new BigDecimal("100"),
                        null,
                        null,
                        claimedOrder.getClientOrderId()
                );

        // ============================================================
        // GIVEN — SOURCE OUTBOX EVENT
        // ============================================================

        UUID sourceEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "ORDER_CREATED"
                );

        OutboxEvent sourceEvent =
                new OutboxEvent(
                        sourceEventId,
                        orderId,
                        1L,
                        "ORDER",
                        "ORDER_CREATED",
                        "{}",
                        signalId,
                        orderId,
                        executionId,
                        signalId,
                        signalId,
                        sourceEventId,
                        1
                );

        UUID completionEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "ORDER_EXECUTED"
                );

        // ============================================================
        // SNAPSHOT — DB STATE BEFORE COMMIT
        // ============================================================

        /*
         * КРИТИЧНО:
         *
         * claimedOrder передаётся непосредственно в commit().
         * commit() мутирует этот объект через order.fill().
         *
         * Поэтому expected values должны быть сняты
         * отдельным чтением из DB ДО commit().
         */
        Order persistedBeforeCommit =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order must exist before commit"
                                )
                        );

        OrderStatus statusBeforeCommit =
                persistedBeforeCommit.getStatus();

        BigDecimal executedQuantityBeforeCommit =
                persistedBeforeCommit.getExecutedQuantity();

        BigDecimal averagePriceBeforeCommit =
                persistedBeforeCommit.getAveragePrice();

        String exchangeOrderIdBeforeCommit =
                persistedBeforeCommit.getExchangeOrderId();

        int executionAttemptsBeforeCommit =
                persistedBeforeCommit.getExecutionAttempts();

        Instant executionStartedAtBeforeCommit =
                persistedBeforeCommit.getExecutionStartedAt();

        UUID lastAppliedExecutionIdBeforeCommit =
                persistedBeforeCommit.getLastAppliedExecutionId();

        UUID executionIdBeforeCommit =
                persistedBeforeCommit.getExecutionId();

        assertEquals(
                OrderStatus.EXECUTING,
                statusBeforeCommit
        );

        assertEquals(
                executionId,
                executionIdBeforeCommit
        );

        // ============================================================
        // FAULT INJECTION
        // ============================================================

        /*
         * ВАЖНО:
         *
         * Fault injection происходит ПОСЛЕ реального
         * ExecutionLockService.markExecuted().
         *
         * Поэтому к моменту exception внутри REQUIRES_NEW TX
         * уже выполнены:
         *
         * 1. Order -> FILLED
         * 2. completion Outbox -> INSERT
         * 3. ExecutionLock -> EXECUTED
         *
         * После exception вся TX обязана rollback.
         */
        doAnswer(invocation -> {

            boolean updated =
                    (boolean) invocation.callRealMethod();

            assertEquals(
                    true,
                    updated,
                    "Execution lock must actually transition to EXECUTED before failure"
            );

            throw new RuntimeException(
                    "SIMULATED_COMMIT_FAILURE_AFTER_LOCK"
            );

        }).when(executionLockService)
                .markExecuted(eq(lockKey));

        // ============================================================
        // WHEN — COMMIT
        // ============================================================

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                commitService.commit(
                                        sourceEvent,
                                        context,
                                        claimedOrder,
                                        result,
                                        lockKey
                                )
                );

        assertEquals(
                "SIMULATED_COMMIT_FAILURE_AFTER_LOCK",
                exception.getMessage(),
                "Expected injected failure after all DB mutations"
        );

        // ============================================================
        // THEN — ORDER ROLLBACK
        // ============================================================

        Order persistedAfterRollback =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order must still exist after rollback"
                                )
                        );

        /*
         * DB state after rollback must exactly equal
         * DB state captured before commit().
         */
        assertEquals(
                statusBeforeCommit,
                persistedAfterRollback.getStatus(),
                "Order status MUST rollback to pre-commit value"
        );

        assertEquals(
                executedQuantityBeforeCommit,
                persistedAfterRollback.getExecutedQuantity(),
                "Executed quantity MUST rollback to pre-commit value"
        );

        assertEquals(
                averagePriceBeforeCommit,
                persistedAfterRollback.getAveragePrice(),
                "Average price MUST rollback to pre-commit value"
        );

        assertEquals(
                exchangeOrderIdBeforeCommit,
                persistedAfterRollback.getExchangeOrderId(),
                "Exchange order id MUST rollback to pre-commit value"
        );

        assertEquals(
                executionAttemptsBeforeCommit,
                persistedAfterRollback.getExecutionAttempts(),
                "Execution attempts MUST rollback to pre-commit value"
        );

        assertEquals(
                executionStartedAtBeforeCommit,
                persistedAfterRollback.getExecutionStartedAt(),
                "Execution started timestamp MUST rollback to pre-commit value"
        );

        assertEquals(
                lastAppliedExecutionIdBeforeCommit,
                persistedAfterRollback.getLastAppliedExecutionId(),
                "lastAppliedExecutionId MUST rollback to pre-commit value"
        );

        assertEquals(
                executionIdBeforeCommit,
                persistedAfterRollback.getExecutionId(),
                "executionId MUST remain immutable"
        );

        // ============================================================
        // THEN — EXECUTION LOCK ROLLBACK
        // ============================================================

        String finalLockState =
                executionLockService.getLockState(lockKey);

        assertEquals(
                "EXECUTING",
                finalLockState,
                "Execution lock EXECUTED transition MUST rollback"
        );

        assertEquals(
                "EXECUTING",
                executionLockRepository
                        .findById(lockKey)
                        .orElseThrow()
                        .getState(),
                "DB execution lock MUST be EXECUTING after rollback"
        );

        // ============================================================
// THEN — OUTBOX ROLLBACK
// ============================================================

        boolean completionEventExists =
                outboxEventRepository.findAll()
                        .stream()
                        .anyMatch(event ->
                                executionId.equals(
                                        event.getExecutionId()
                                )
                                        && !"ORDER_CREATED".equals(
                                        event.getEventType()
                                )
                        );

        assertFalse(
                completionEventExists,
                "Completion Outbox event MUST rollback. " +
                        "No completion event for executionId=" +
                        executionId +
                        " must remain after failed execution commit"
        );

        // ============================================================
        // THEN — COMPENSATION WAS PART OF TRANSACTION
        // ============================================================

        verify(
                orderCompensationService,
                times(1)
        ).consumeReservation(
                any(Order.class),
                eq("Order fully filled")
        );
    }
}