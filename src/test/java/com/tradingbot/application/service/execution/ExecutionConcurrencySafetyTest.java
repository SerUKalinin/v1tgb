package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
            boolean completed = latch.await(
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
            Throwable cause = e.getCause();

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
                persistOrder(OrderStatus.PENDING_EXECUTION);

        UUID orderId = persistedOrder.getId();

        ExecutionContext executionContext =
                contextOf(persistedOrder);

        CountDownLatch executionClaimed =
                new CountDownLatch(1);

        AtomicBoolean reconciliationClaimed =
                new AtomicBoolean(false);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<?> executionFuture = executor.submit(() -> {

            Optional<Order> executionOrder =
                    orderRepositoryAdapter.claimForExecution(
                            orderId,
                            executionContext
                    );

            Assertions.assertThat(executionOrder)
                    .as("execution worker must acquire PENDING_EXECUTION order")
                    .isPresent();

            /*
             * Важно:
             * claimForExecution() уже завершил REQUIRED transaction
             * к моменту возврата из метода.
             *
             * Следовательно после countDown row уже находится
             * в EXECUTING и ownership принадлежит execution worker.
             */
            executionClaimed.countDown();

            Order order = executionOrder.orElseThrow();

            order.fill(
                    executionContext,
                    "exchange-order-execution",
                    BigDecimal.ONE,
                    new BigDecimal("10001")
            );

            orderRepositoryAdapter.save(order);
        });

        Future<?> reconciliationFuture = executor.submit(() -> {

            await(
                    executionClaimed,
                    Duration.ofSeconds(10),
                    "execution worker did not claim order in time"
            );

            Optional<Order> reconciliationOrder =
                    orderRepositoryAdapter.claimForReconciliation(orderId);

            reconciliationClaimed.set(
                    reconciliationOrder.isPresent()
            );
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
                .as("execution worker must finish the order")
                .isEqualTo(OrderStatus.FILLED);

        Assertions.assertThat(
                        reconciliationClaimed.get()
                )
                .as("reconciliation must not steal an active execution")
                .isFalse();

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
                persistOrder(OrderStatus.PENDING_EXECUTION);

        UUID orderId = persistedOrder.getId();

        ExecutionContext executionContext =
                contextOf(persistedOrder);

        /*
         * Получаем detached persistence snapshot
         * с исходной @Version.
         */
        OrderEntity staleEntity =
                orderRepository.findById(orderId)
                        .orElseThrow();

        Long staleVersion =
                staleEntity.getVersion();

        /*
         * Execution worker меняет Order.
         *
         * Hibernate увеличивает @Version при flush.
         */
        Order executionOrder =
                orderRepositoryAdapter.claimForExecution(
                                orderId,
                                executionContext
                        )
                        .orElseThrow();

        Assertions.assertThat(
                        executionOrder.getStatus()
                )
                .isEqualTo(OrderStatus.EXECUTING);

        /*
         * Не используем executionOrder.getVersion():
         * это domain snapshot, созданный до Hibernate version increment.
         *
         * Читаем authoritative persistence state из БД.
         */
        OrderEntity currentEntity =
                orderRepository.findById(orderId)
                        .orElseThrow();

        Long currentVersion =
                currentEntity.getVersion();

        Assertions.assertThat(currentVersion)
                .as("Hibernate @Version must increase after execution claim")
                .isGreaterThan(staleVersion);

        /*
         * Теперь stale detached entity пытается записаться
         * с устаревшей @Version.
         */
        staleEntity.setStatus(OrderStatus.REJECTED);
        staleEntity.setUpdatedAt(Instant.now());

        Assertions.assertThatThrownBy(
                        () -> orderRepository.saveAndFlush(staleEntity)
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
                persistOrder(OrderStatus.FILLED);

        ExecutionContext context =
                contextOf(persistedOrder);

        Order terminalOrder =
                orderRepositoryAdapter.findById(
                                persistedOrder.getId()
                        )
                        .orElseThrow();

        Assertions.assertThatThrownBy(
                        () -> terminalOrder.markAsRejected(
                                context,
                                "illegal"
                        )
                )
                .as("FILLED must remain terminal")
                .isInstanceOf(IllegalStateException.class);

        Order finalState =
                orderRepositoryAdapter.findById(
                                persistedOrder.getId()
                        )
                        .orElseThrow();

        Assertions.assertThat(
                        finalState.getStatus()
                )
                .isEqualTo(OrderStatus.FILLED);
    }

    // ============================================================
    // 4. UNKNOWN CONCURRENT FINALIZERS
    // ============================================================

    @Test
    void unknownLifecycleWithConcurrentFinalizers() {

        Order persistedOrder =
                persistOrder(OrderStatus.UNKNOWN);

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
         * Worker #1: exchange reconciliation result = FILLED
         */
        futures.add(
                executor.submit(() -> {

                    await(
                            start,
                            Duration.ofSeconds(10),
                            "FILLED worker start barrier timed out"
                    );

                    Order order =
                            orderRepositoryAdapter.findById(orderId)
                                    .orElseThrow();

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
         * Worker #2: exchange reconciliation result = REJECTED
         */
        futures.add(
                executor.submit(() -> {

                    await(
                            start,
                            Duration.ofSeconds(10),
                            "REJECTED worker start barrier timed out"
                    );

                    Order order =
                            orderRepositoryAdapter.findById(orderId)
                                    .orElseThrow();

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
         * Оба перехода UNKNOWN -> FILLED и
         * UNKNOWN -> REJECTED разрешены state machine.
         *
         * Конкретный победитель зависит от serialisation
         * двух finalizer transactions.
         */
        Assertions.assertThat(
                        finalState.getStatus()
                )
                .as(
                        "UNKNOWN must not remain unresolved after concurrent finalizers"
                )
                .isIn(
                        OrderStatus.FILLED,
                        OrderStatus.REJECTED
                );

        Assertions.assertThat(
                        successfulFinalizations.get()
                )
                .isGreaterThanOrEqualTo(1);
    }

    // ============================================================
    // 5. ONLY ONE EXECUTION WORKER MAY CLAIM THE ORDER
    // ============================================================

    @Test
    void doubleExecutionAttemptOnlyOneClaimSucceeds() {

        Order persistedOrder =
                persistOrder(OrderStatus.PENDING_EXECUTION);

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
                .isEqualTo(OrderStatus.EXECUTING);

        Assertions.assertThat(
                        finalState.getExecutionId()
                )
                .isEqualTo(
                        executionContext.attempt().executionId()
                );
    }
}