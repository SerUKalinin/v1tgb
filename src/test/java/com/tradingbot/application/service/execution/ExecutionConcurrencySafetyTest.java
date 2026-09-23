package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.adapter.OrderRepositoryAdapter;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.testutil.TestOrderFactory;
import com.tradingbot.tracing.ExecutionContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExecutionConcurrencySafetyTest extends BaseIntegrationTest {

    @Autowired
    private OrderRepositoryAdapter orderRepositoryAdapter;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    private TestOrderFactory orderFactory;

    @BeforeEach
    void setUp() {
        orderFactory = new TestOrderFactory(
                orderRepository,
                orderMapper
        );
    }

    private Order persistOrder(OrderStatus status) {
        return orderFactory.create(status);
    }

    private ExecutionContext contextOf(Order order) {
        return ExecutionContext.of(order);
    }

    private void await(
            CountDownLatch latch,
            Duration timeout,
            String message
    ) {
        try {
            boolean completed =
                    latch.await(
                            timeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    );

            Assertions.assertThat(completed)
                    .as(message)
                    .isTrue();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while waiting for concurrency barrier",
                    e
            );
        }
    }

    private void shutdown(ExecutorService executor) {
        executor.shutdownNow();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError(
                        "Executor did not terminate within timeout"
                );
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while shutting down executor",
                    e
            );
        }
    }

    private void rethrowFutureFailure(Future<?> future) {
        try {
            future.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while waiting for concurrent worker",
                    e
            );

        } catch (ExecutionException e) {

            Throwable cause =
                    e.getCause();

            if (cause instanceof AssertionError assertionError) {
                throw assertionError;
            }

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new AssertionError(
                    "Concurrent worker failed",
                    cause
            );
        }
    }

    // ============================================================
    // 1. EXECUTION MUST WIN AGAINST RECONCILIATION
    // ============================================================

    @Test
    void executionAlwaysWinsReconciliationRace() {

        Order persistedOrder =
                persistOrder(
                        OrderStatus.PENDING_EXECUTION
                );

        UUID orderId =
                persistedOrder.getId();

        ExecutionContext executionContext =
                contextOf(persistedOrder);

        CountDownLatch executionClaimed =
                new CountDownLatch(1);

        AtomicInteger reconciliationClaims =
                new AtomicInteger(0);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<?> executionFuture =
                executor.submit(() -> {

                    Optional<Order> executionOrder =
                            orderRepositoryAdapter.claimForExecution(
                                    orderId,
                                    executionContext
                            );

                    Assertions.assertThat(executionOrder)
                            .as(
                                    "execution worker must acquire PENDING_EXECUTION order"
                            )
                            .isPresent();

                    executionClaimed.countDown();

                    Order order =
                            executionOrder.orElseThrow();

                    order.fill(
                            executionContext,
                            "exchange-order-execution",
                            BigDecimal.ONE,
                            new BigDecimal("10001")
                    );

                    orderRepositoryAdapter.save(order);
                });

        Future<?> reconciliationFuture =
                executor.submit(() -> {

                    await(
                            executionClaimed,
                            Duration.ofSeconds(10),
                            "execution worker did not claim order in time"
                    );

                    Optional<Order> reconciliationOrder =
                            orderRepositoryAdapter.claimForReconciliation(
                                    orderId
                            );

                    if (reconciliationOrder.isPresent()) {
                        reconciliationClaims.incrementAndGet();
                    }
                });

        try {
            rethrowFutureFailure(executionFuture);
            rethrowFutureFailure(reconciliationFuture);

        } finally {
            shutdown(executor);
        }

        Order finalState =
                orderRepositoryAdapter.findById(orderId)
                        .orElseThrow();

        Assertions.assertThat(
                        finalState.getStatus()
                )
                .as(
                        "execution worker must finish the order"
                )
                .isEqualTo(OrderStatus.FILLED);

        Assertions.assertThat(
                        reconciliationClaims.get()
                )
                .as(
                        "reconciliation must not steal an active execution"
                )
                .isZero();

        Assertions.assertThat(
                        finalState.getExecutionId()
                )
                .isEqualTo(
                        executionContext.attempt().executionId()
                );
    }

    // ============================================================
    // 2. REAL @VERSION STALE ENTITY MUST BE REJECTED
    // ============================================================

    @Test
    void staleVersionReconciliationIsRejected() {

        Order persistedOrder =
                persistOrder(
                        OrderStatus.PENDING_EXECUTION
                );

        UUID orderId =
                persistedOrder.getId();

        ExecutionContext executionContext =
                contextOf(persistedOrder);

        OrderEntity staleEntity =
                orderRepository.findById(orderId)
                        .orElseThrow();

        Long staleVersion =
                staleEntity.getVersion();

        Order executionOrder =
                orderRepositoryAdapter.claimForExecution(
                                orderId,
                                executionContext
                        )
                        .orElseThrow();

        Assertions.assertThat(
                        executionOrder.getStatus()
                )
                .isEqualTo(
                        OrderStatus.EXECUTING
                );

        OrderEntity currentEntity =
                orderRepository.findById(orderId)
                        .orElseThrow();

        Long currentVersion =
                currentEntity.getVersion();

        Assertions.assertThat(currentVersion)
                .as(
                        "Hibernate @Version must increase after execution claim"
                )
                .isGreaterThan(staleVersion);

        staleEntity.setStatus(
                OrderStatus.REJECTED
        );

        staleEntity.setUpdatedAt(
                Instant.now()
        );

        Assertions.assertThatThrownBy(
                        () ->
                                orderRepository.saveAndFlush(
                                        staleEntity
                                )
                )
                .as(
                        "stale persistence snapshot must be rejected by @Version"
                )
                .isInstanceOf(
                        ObjectOptimisticLockingFailureException.class
                );
    }

    // ============================================================
    // 3. TERMINAL STATES ARE IMMUTABLE
    // ============================================================

    @Test
    void terminalStatesAreImmutableUnderRace() {

        Order persistedOrder =
                persistOrder(
                        OrderStatus.FILLED
                );

        ExecutionContext context =
                contextOf(persistedOrder);

        Order terminalOrder =
                orderRepositoryAdapter.findById(
                                persistedOrder.getId()
                        )
                        .orElseThrow();

        Assertions.assertThatThrownBy(
                        () ->
                                terminalOrder.markAsRejected(
                                        context,
                                        "illegal"
                                )
                )
                .as(
                        "FILLED must remain terminal"
                )
                .isInstanceOf(
                        IllegalStateException.class
                );

        Order finalState =
                orderRepositoryAdapter.findById(
                                persistedOrder.getId()
                        )
                        .orElseThrow();

        Assertions.assertThat(
                        finalState.getStatus()
                )
                .isEqualTo(
                        OrderStatus.FILLED
                );
    }

    // ============================================================
    // 4. UNKNOWN CONCURRENT FINALIZERS
    // ============================================================

    @Test
    void unknownLifecycleWithConcurrentFinalizers() {

        Order persistedOrder =
                persistOrder(
                        OrderStatus.UNKNOWN
                );

        UUID orderId =
                persistedOrder.getId();

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successfulFinalizations =
                new AtomicInteger(0);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        List<Future<?>> futures =
                new ArrayList<>();

        /*
         * Worker #1:
         * пытается получить ownership reconciliation,
         * затем завершает UNKNOWN -> FILLED.
         */
        futures.add(
                executor.submit(() -> {

                    await(
                            start,
                            Duration.ofSeconds(10),
                            "FILLED worker start barrier timed out"
                    );

                    Optional<Order> claimed =
                            orderRepositoryAdapter
                                    .claimForReconciliation(
                                            orderId
                                    );

                    /*
                     * Второй worker может не получить claim.
                     * Это нормальный результат гонки.
                     */
                    if (claimed.isEmpty()) {
                        return;
                    }

                    Order order =
                            claimed.get();

                    Assertions.assertThat(
                                    order.getStatus()
                            )
                            .as(
                                    "claimed UNKNOWN order must enter RECOVERING"
                            )
                            .isEqualTo(
                                    OrderStatus.RECOVERING
                            );

                    ExecutionContext context =
                            contextOf(order);

                    order.fill(
                            context,
                            "exchange-order-filled",
                            BigDecimal.ONE,
                            new BigDecimal("10003")
                    );

                    orderRepositoryAdapter.save(order);

                    successfulFinalizations.incrementAndGet();
                })
        );

        /*
         * Worker #2:
         * пытается получить тот же reconciliation ownership,
         * затем завершает UNKNOWN -> REJECTED.
         */
        futures.add(
                executor.submit(() -> {

                    await(
                            start,
                            Duration.ofSeconds(10),
                            "REJECTED worker start barrier timed out"
                    );

                    Optional<Order> claimed =
                            orderRepositoryAdapter
                                    .claimForReconciliation(
                                            orderId
                                    );

                    /*
                     * Проигравший worker должен просто получить
                     * Optional.empty().
                     */
                    if (claimed.isEmpty()) {
                        return;
                    }

                    Order order =
                            claimed.get();

                    Assertions.assertThat(
                                    order.getStatus()
                            )
                            .as(
                                    "claimed UNKNOWN order must enter RECOVERING"
                            )
                            .isEqualTo(
                                    OrderStatus.RECOVERING
                            );

                    ExecutionContext context =
                            contextOf(order);

                    order.markAsRejected(
                            context,
                            "exchange"
                    );

                    orderRepositoryAdapter.save(order);

                    successfulFinalizations.incrementAndGet();
                })
        );

        start.countDown();

        try {

            for (Future<?> future : futures) {
                rethrowFutureFailure(future);
            }

        } finally {
            shutdown(executor);
        }

        Order finalState =
                orderRepositoryAdapter.findById(orderId)
                        .orElseThrow();

        /*
         * Должен победить ровно один finalizer.
         */
        Assertions.assertThat(
                        successfulFinalizations.get()
                )
                .as(
                        "exactly one reconciliation worker may finalize UNKNOWN order"
                )
                .isEqualTo(1);

        Assertions.assertThat(
                        finalState.getStatus()
                )
                .as(
                        "UNKNOWN must not remain unresolved after reconciliation race"
                )
                .isIn(
                        OrderStatus.FILLED,
                        OrderStatus.REJECTED
                );
    }

    // ============================================================
    // 5. ONLY ONE EXECUTION WORKER MAY CLAIM THE ORDER
    // ============================================================

    @Test
    void doubleExecutionAttemptOnlyOneClaimSucceeds() {

        Order persistedOrder =
                persistOrder(
                        OrderStatus.PENDING_EXECUTION
                );

        UUID orderId =
                persistedOrder.getId();

        ExecutionContext executionContext =
                contextOf(persistedOrder);

        int threads = 5;

        ExecutorService executor =
                Executors.newFixedThreadPool(threads);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successCount =
                new AtomicInteger(0);

        List<Future<?>> futures =
                new ArrayList<>();

        for (int i = 0; i < threads; i++) {

            futures.add(
                    executor.submit(() -> {

                        await(
                                start,
                                Duration.ofSeconds(10),
                                "execution claim barrier timed out"
                        );

                        Optional<Order> claimed =
                                orderRepositoryAdapter.claimForExecution(
                                        orderId,
                                        executionContext
                                );

                        if (claimed.isPresent()) {
                            successCount.incrementAndGet();
                        }
                    })
            );
        }

        start.countDown();

        try {

            for (Future<?> future : futures) {
                rethrowFutureFailure(future);
            }

        } finally {
            shutdown(executor);
        }

        Assertions.assertThat(
                        successCount.get()
                )
                .as(
                        "exactly one execution worker may claim one Order"
                )
                .isEqualTo(1);

        Order finalState =
                orderRepositoryAdapter.findById(orderId)
                        .orElseThrow();

        Assertions.assertThat(
                        finalState.getStatus()
                )
                .isEqualTo(
                        OrderStatus.EXECUTING
                );

        Assertions.assertThat(
                        finalState.getExecutionId()
                )
                .isEqualTo(
                        executionContext.attempt().executionId()
                );
    }
}