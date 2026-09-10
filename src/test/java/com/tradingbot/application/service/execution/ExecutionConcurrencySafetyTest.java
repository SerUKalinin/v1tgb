package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.adapter.OrderRepositoryAdapter;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.testutil.TestOrderFactory;
import com.tradingbot.tracing.ExecutionContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

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
    private ExecutionContext mockCtx;

    @BeforeEach
    void setUp() {
        orderFactory = new TestOrderFactory(orderRepository, orderMapper);
        mockCtx = mock(ExecutionContext.class);
    }

    private Order persistOrder(OrderStatus status) {
        return orderFactory.create(status);
    }

    // =========================
    // UTILS
    // =========================

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // =========================
    // TESTS
    // =========================

    @Test
    void executionAlwaysWinsReconciliationRace() {
        Order entity = persistOrder(OrderStatus.PENDING_EXECUTION);

        CountDownLatch reconStart = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        AtomicBoolean reconClaimed = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            executor.submit(() -> {
                Optional<Order> executionOrder =
                        orderRepositoryAdapter.claimForExecution(entity.getId(), mockCtx);

                Assertions.assertThat(executionOrder).isPresent();

                Order order = executionOrder.get();

                reconStart.countDown();

                order.fill(mockCtx, "ex-1", BigDecimal.ONE, new BigDecimal("10001"));
                orderRepositoryAdapter.save(order);

                finished.countDown();
            });

            executor.submit(() -> {
                await(reconStart);

                Optional<Order> reconOrder =
                        orderRepositoryAdapter.claimForReconciliation(entity.getId());

                reconClaimed.set(reconOrder.isPresent());

                reconOrder.ifPresent(o -> {
                    o.markAsRejected(mockCtx, "race");
                    try {
                        orderRepositoryAdapter.save(o);
                    } catch (Exception ignored) {}
                });

                finished.countDown();
            });

            await(finished);

        } finally {
            executor.shutdownNow();
        }

        Order finalState =
                orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

        Assertions.assertThat(finalState.getStatus())
                .isEqualTo(OrderStatus.FILLED);

        Assertions.assertThat(reconClaimed.get())
                .isFalse();
    }

    @Test
    void staleVersionReconciliationIsRejected() {
        Order entity = persistOrder(OrderStatus.PENDING_EXECUTION);

        Order staleSnapshot =
                orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> {
                Order executionOrder =
                        orderRepositoryAdapter.claimForExecution(entity.getId(), mockCtx)
                                .orElseThrow();

                executionOrder.fill(mockCtx, "ex-2", BigDecimal.ONE, new BigDecimal("10002"));
                orderRepositoryAdapter.save(executionOrder);
            }).get();

        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }

        // корректное поведение — state machine reject
        Assertions.assertThatThrownBy(() -> {
            staleSnapshot.markAsRejected(mockCtx, "stale");
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatesAreImmutableUnderRace() {
        Order entity = persistOrder(OrderStatus.FILLED);

        Order terminalOrder =
                orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

        Assertions.assertThatThrownBy(() ->
                terminalOrder.markAsRejected(mockCtx, "illegal")
        );

        Order finalState =
                orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

        Assertions.assertThat(finalState.getStatus())
                .isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void unknownLifecycleWithConcurrentFinalizers() {
        Order entity = persistOrder(OrderStatus.UNKNOWN);

        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            await(start);

            Order executionOrder =
                    orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

            executionOrder.fill(mockCtx, "ex-3", BigDecimal.ONE, new BigDecimal("10003"));
            orderRepositoryAdapter.save(executionOrder);
        }));

        futures.add(executor.submit(() -> {
            await(start);

            Order reconOrder =
                    orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

            reconOrder.markAsRejected(mockCtx, "exchange");

            try {
                orderRepositoryAdapter.save(reconOrder);
            } catch (Exception ignored) {}
        }));

        start.countDown();

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {}
        }

        executor.shutdownNow();

        Order finalState =
                orderRepositoryAdapter.findById(entity.getId()).orElseThrow();

        Assertions.assertThat(finalState.getStatus())
                .isIn(OrderStatus.FILLED, OrderStatus.REJECTED);
    }

    @Test
    void doubleExecutionAttemptOnlyOneClaimSucceeds() {
        Order entity = persistOrder(OrderStatus.PENDING_EXECUTION);

        int threads = 5;

        ExecutorService executor =
                Executors.newFixedThreadPool(threads);

        CountDownLatch latch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                await(latch);

                if (orderRepositoryAdapter.claimForExecution(entity.getId(), mockCtx).isPresent()) {
                    successCount.incrementAndGet();
                }
            }));
        }

        latch.countDown();

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {}
        }

        executor.shutdownNow();

        Assertions.assertThat(successCount.get())
                .isEqualTo(1);
    }
}
