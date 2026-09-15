package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ClaimVsWatchdogRaceTest {

    @Autowired
    private OrderRepositoryPort orderRepositoryPort;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    /*
     * Не даём реальному bootstrap запускать Risk recovery / reconciliation
     * и ходить на Binance во время integration test.
     */
    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @MockBean
    private ReconciliationService reconciliationService;

    @Test
    void staleExecutingOrderMustNotBeClaimableByExecutionAfterReconciliationClaim() {

        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Instant staleTime = Instant.now().minusSeconds(180);

        OrderEntity entity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId("TEST-" + orderId)
                .exchangeOrderId(null)
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(BigDecimal.ONE)
                .price(BigDecimal.ZERO)
                .stopLoss(null)
                .takeProfit(null)
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .strategyId("claim-watchdog-race-test")
                .signalId(signalId)
                .executionId(executionId)
                .executionStartedAt(staleTime)
                .executionAttempts(1)
                .rejectionReason(null)
                .status(OrderStatus.EXECUTING)
                .createdAt(staleTime)
                .updatedAt(staleTime)
                .lastAppliedExecutionId(null)
                .version(0L)
                .build();

        orderRepository.saveAndFlush(entity);

        /*
         * Проверяем исходное состояние.
         */
        Order order = orderRepositoryPort
                .findById(orderId)
                .orElseThrow(() ->
                        new AssertionError("Test order was not persisted"));

        assertEquals(
                OrderStatus.EXECUTING,
                order.getStatus()
        );

        assertEquals(
                executionId,
                order.getExecutionId()
        );

        ExecutionContext context = ExecutionContext.of(order);

        /*
         * ================================================================
         * STEP 1
         * ================================================================
         *
         * Reconciliation получает stale EXECUTING order.
         *
         * Это реальный OrderRepositoryAdapter.claimForReconciliation(),
         * а не Mockito.
         */
        Optional<Order> reconciliationClaim =
                orderRepositoryPort.claimForReconciliation(orderId);

        assertTrue(
                reconciliationClaim.isPresent(),
                "Stale EXECUTING order must be claimable by reconciliation"
        );

        /*
         * ================================================================
         * STEP 2
         * ================================================================
         *
         * После завершения REQUIRES_NEW transaction reconciliation claim
         * должен сохранять ownership над order.
         *
         * Execution НЕ должен получить возможность повторно захватить
         * тот же lifecycle.
         */
        Optional<Order> executionClaim =
                orderRepositoryPort.claimForExecution(
                        orderId,
                        context
                );

        assertTrue(
                executionClaim.isEmpty(),
                """
                DOUBLE OWNERSHIP BUG:
                reconciliation claimed a stale EXECUTING order,
                but execution was able to claim the same order again.
                """
        );
    }

    @Test
    void unknownOrderMustBeClaimableByOnlyOneReconciliationWorker() throws Exception {

        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Instant staleTime = Instant.now().minusSeconds(180);

        OrderEntity entity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId("TEST-RECON-RACE-" + orderId)
                .exchangeOrderId(null)
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(BigDecimal.ONE)
                .price(BigDecimal.ZERO)
                .stopLoss(null)
                .takeProfit(null)
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .strategyId("reconciliation-race-test")
                .signalId(signalId)
                .executionId(executionId)
                .executionStartedAt(staleTime)
                .executionAttempts(1)
                .rejectionReason(null)
                .status(OrderStatus.UNKNOWN)
                .createdAt(staleTime)
                .updatedAt(staleTime)
                .lastAppliedExecutionId(null)
                .version(0L)
                .build();

        orderRepository.saveAndFlush(entity);

        /*
         * Проверяем, что исходное состояние действительно UNKNOWN.
         */
        Order persistedOrder = orderRepositoryPort
                .findById(orderId)
                .orElseThrow(() ->
                        new AssertionError("Test order was not persisted"));

        assertEquals(
                OrderStatus.UNKNOWN,
                persistedOrder.getStatus()
        );

        /*
         * Два независимых worker-а одновременно пытаются получить
         * reconciliation ownership одного и того же lifecycle.
         *
         * Каждый вызов orderRepositoryPort.claimForReconciliation()
         * проходит через Spring proxy и получает собственную
         * REQUIRES_NEW transaction.
         */
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Optional<Order>> worker1 =
                    executor.submit(() -> {

                        ready.countDown();

                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Worker 1 was not released"
                            );
                        }

                        return orderRepositoryPort
                                .claimForReconciliation(orderId);
                    });

            Future<Optional<Order>> worker2 =
                    executor.submit(() -> {

                        ready.countDown();

                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Worker 2 was not released"
                            );
                        }

                        return orderRepositoryPort
                                .claimForReconciliation(orderId);
                    });

            /*
             * Гарантируем, что оба worker-а дошли до точки старта.
             */
            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both reconciliation workers must become ready"
            );

            /*
             * Одновременно отпускаем оба вызова.
             */
            start.countDown();

            Optional<Order> result1 =
                    worker1.get(10, TimeUnit.SECONDS);

            Optional<Order> result2 =
                    worker2.get(10, TimeUnit.SECONDS);

            int successfulClaims =
                    (result1.isPresent() ? 1 : 0)
                            + (result2.isPresent() ? 1 : 0);

            /*
             * Один lifecycle должен иметь только одного reconciliation owner.
             */
            assertEquals(
                    1,
                    successfulClaims,
                    """
                    DOUBLE RECONCILIATION OWNERSHIP BUG:
                    two concurrent reconciliation workers were able
                    to claim the same UNKNOWN order.
                    """
            );

        } finally {
            executor.shutdownNow();

            assertTrue(
                    executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Reconciliation race executor did not terminate"
            );
        }
    }
}