package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconciliationClaimRaceTest {

    private ReconciliationService reconciliationService;
    private OrderRepositoryPort orderRepository;
    private ExchangeOrderQueryService exchangeQueryService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepositoryPort.class);
        exchangeQueryService = mock(ExchangeOrderQueryService.class);

        reconciliationService = new ReconciliationService(
                orderRepository,
                mock(OutboxEventRepository.class),
                exchangeQueryService,
                mock(OrderCompensationService.class),
                mock(RiskEngine.class),
                mock(AdminNotificationService.class),
                mock(PositionRebuildService.class),
                mock(SystemStateManager.class),
                mock(TransitionValidator.class),
                mock(ExecutionLogger.class)
        );
    }

    @Test
    void shouldAllowOnlyOneWorkerToReconcileSameSentOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();

        Order order = Order.createPendingExecution(
                orderId,
                "client-race-1",
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                "strategy-race",
                signalId
        );

        /*
         * Формируем валидный lifecycle:
         *
         * PENDING_EXECUTION
         *        ↓
         *    EXECUTING
         *        ↓
         * SENT_TO_EXCHANGE
         *
         * Только после этого reconciliation имеет право
         * перевести ордер в FILLED.
         */
        ExecutionContext context = ExecutionContext.of(order);

        order.markExecuting(context);
        order.markAccepted(context, "ex-race-initial");

        assertEquals(
                OrderStatus.SENT_TO_EXCHANGE,
                order.getStatus()
        );

        AtomicBoolean claimed = new AtomicBoolean(false);
        AtomicInteger claimCount = new AtomicInteger(0);
        AtomicInteger exchangeQueryCount = new AtomicInteger(0);

        /*
         * Моделируем контракт claimForReconciliation():
         *
         * первый worker получает ownership;
         * второй worker получает EMPTY.
         *
         * Это именно тот контракт, который production-реализация
         * должна обеспечить через SELECT FOR UPDATE +
         * durable ownership barrier.
         */
        when(orderRepository.claimForReconciliation(orderId))
                .thenAnswer(invocation -> {
                    claimCount.incrementAndGet();

                    if (claimed.compareAndSet(false, true)) {
                        return Optional.of(order);
                    }

                    return Optional.empty();
                });

        when(exchangeQueryService.getOrderStatus("client-race-1"))
                .thenAnswer(invocation -> {
                    exchangeQueryCount.incrementAndGet();

                    return ExecutionResult.filled(
                            orderId,
                            "ex-race-filled",
                            "trade-race-1",
                            "BTCUSDT",
                            OrderSide.BUY,
                            BigDecimal.ONE,
                            BigDecimal.valueOf(50000),
                            BigDecimal.ZERO,
                            "USDT",
                            "client-race-1"
                    );
                });

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch start =
                new CountDownLatch(1);

        CountDownLatch finished =
                new CountDownLatch(2);

        executor.submit(() -> {
            try {
                start.await();

                reconciliationService.syncOrderWithExchange(
                        order,
                        context
                );

            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                finished.countDown();
            }
        });

        executor.submit(() -> {
            try {
                start.await();

                reconciliationService.syncOrderWithExchange(
                        order,
                        context
                );

            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                finished.countDown();
            }
        });

        /*
         * Одновременно запускаем оба reconciliation worker.
         */
        start.countDown();

        assertTrue(
                finished.await(10, TimeUnit.SECONDS),
                "Reconciliation workers did not finish in time"
        );

        executor.shutdownNow();

        /*
         * Оба worker должны попытаться получить ownership.
         */
        assertEquals(
                2,
                claimCount.get(),
                "Both reconciliation workers must attempt to claim the order"
        );

        /*
         * Но только один worker должен получить ownership
         * и обратиться к бирже.
         */
        assertEquals(
                1,
                exchangeQueryCount.get(),
                "Only one reconciliation worker may query the exchange"
        );

        /*
         * Единственный владелец должен применить результат
         * reconciliation.
         */
        assertEquals(
                OrderStatus.FILLED,
                order.getStatus()
        );

        assertEquals(
                "ex-race-filled",
                order.getExchangeOrderId()
        );

        assertEquals(
                BigDecimal.ONE,
                order.getExecutedQuantity()
        );

        assertEquals(
                BigDecimal.valueOf(50000),
                order.getAveragePrice()
        );

        /*
         * Финальный результат должен быть сохранён ровно один раз.
         */
        verify(
                orderRepository,
                times(1)
        ).save(order);
    }
}