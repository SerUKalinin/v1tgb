package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.*;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
        properties = {
                "app.outbox.enabled=true",
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("test")
class ConcurrentUnknownRecoverySettlementIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderRepositoryPort orderRepositoryPort;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private SignalExecutionFacade signalExecutionFacade;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private ReconciliationService reconciliationService;

    @MockBean
    private ExchangeFeasibilityPort feasibilityPort;

    @MockBean
    private OrderNormalizationService normalizationService;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @MockBean
    private ExchangeOrderQueryService exchangeQueryService;

    @BeforeEach
    void prepareTestEnvironment() {

        when(systemStateManager.isTradingEnabled())
                .thenReturn(true);

        when(systemStateManager.isReady())
                .thenReturn(true);

        RiskStateEntity riskState =
                riskStateRepository
                        .findById(
                                RiskStateEntity.SINGLETON_ID
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "GLOBAL RiskState должен быть создан BaseIntegrationTest"
                                )
                        );

        riskState.setTotalEquity(
                new BigDecimal("10000")
        );

        riskState.setAvailableBalance(
                new BigDecimal("10000")
        );

        riskState.setReservedMargin(
                BigDecimal.ZERO
        );

        riskState.setHalted(false);

        riskState.setActiveReservations(
                new HashMap<>()
        );

        riskState.setUpdatedAt(
                Instant.now()
        );

        riskStateRepository.saveAndFlush(
                riskState
        );

        when(
                normalizationService.normalize(
                        any(FeasibilityRequest.class)
                )
        ).thenAnswer(invocation -> {

            FeasibilityRequest request =
                    invocation.getArgument(
                            0,
                            FeasibilityRequest.class
                    );

            return new NormalizedOrder(
                    request.getSymbol(),
                    request.getQuantity(),
                    request.getPrice()
            );
        });

        when(
                feasibilityPort.check(
                        any(FeasibilityRequest.class)
                )
        ).thenReturn(
                FeasibilityResult.success()
        );

        /*
         * Initial placement is deliberately ambiguous.
         */
        when(
                executionPort.placeOrder(
                        any(Order.class)
                )
        ).thenAnswer(invocation -> {

            Order order =
                    invocation.getArgument(
                            0,
                            Order.class
                    );

            return ExecutionResult.exchangeStateUnknown(
                    order.getId()
            );
        });
    }

    @Test
    void concurrentUnknownRecoveryMustSettleExactlyOnce()
            throws Exception {

        UUID signalId =
                UUID.randomUUID();

        SignalEvent signal =
                new SignalEvent(
                        signalId,
                        "BTCUSDT",
                        SignalType.BUY,
                        new BigDecimal("100"),
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        Instant.now(),
                        "CONCURRENT-UNKNOWN-RECOVERY-TEST"
                );

        signalExecutionFacade.execute(signal);

        OrderEntity createdOrder =
                waitForOrder(signalId);

        UUID orderId =
                createdOrder.getId();

        RiskStateEntity afterReservation =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterReservation.getTotalEquity(),
                "totalEquity после reservation"
        );

        assertBigDecimal(
                "9900",
                afterReservation.getAvailableBalance(),
                "availableBalance после reservation"
        );

        assertBigDecimal(
                "100",
                afterReservation.getReservedMargin(),
                "reservedMargin после reservation"
        );

        drainOutbox();

        OrderEntity unknownOrder =
                waitForStatus(
                        signalId,
                        OrderStatus.UNKNOWN
                );

        UUID executionId =
                unknownOrder.getExecutionId();

        assertNotNull(
                executionId,
                "executionId должен существовать"
        );

        assertBigDecimal(
                "0",
                unknownOrder.getExecutedQuantity(),
                "executedQuantity в UNKNOWN"
        );

        Order unknownDomainOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден в UNKNOWN"
                                )
                        );

        ExecutionContext context =
                ExecutionContext.of(
                        unknownDomainOrder
                );

        AtomicInteger exchangeQueryCount =
                new AtomicInteger();

        CountDownLatch exchangeQueryStarted =
                new CountDownLatch(1);

        CountDownLatch releaseExchangeQuery =
                new CountDownLatch(1);

        when(
                exchangeQueryService.getOrderStatus(
                        unknownDomainOrder.getSymbol(),
                        unknownDomainOrder.getClientOrderId()
                )
        ).thenAnswer(invocation -> {

            int invocationNumber =
                    exchangeQueryCount.incrementAndGet();

            exchangeQueryStarted.countDown();

            /*
             * Первый worker удерживает exchange I/O,
             * чтобы второй worker успел столкнуться с ownership claim.
             */
            if (invocationNumber == 1) {

                try {

                    if (!releaseExchangeQuery.await(
                            10,
                            TimeUnit.SECONDS
                    )) {

                        throw new AssertionError(
                                "Timed out waiting for exchange-query release"
                        );
                    }

                } catch (InterruptedException e) {

                    Thread.currentThread().interrupt();

                    throw new AssertionError(
                            "Exchange query worker interrupted",
                            e
                    );
                }
            }

            return ExecutionResult.partiallyFilled(
                    orderId,
                    "EXCHANGE-CONCURRENT",
                    "TRADE-CONCURRENT",
                    unknownDomainOrder.getSymbol(),
                    unknownDomainOrder.getSide(),
                    new BigDecimal("0.3"),
                    new BigDecimal("100"),
                    unknownDomainOrder.getClientOrderId()
            );
        });

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        Future<?> worker1 = null;
        Future<?> worker2 = null;

        try {

            worker1 =
                    executor.submit(
                            () ->
                                    reconciliationService.reconcile(
                                            context
                                    )
                    );

            worker2 =
                    executor.submit(
                            () ->
                                    reconciliationService.reconcile(
                                            context
                                    )
                    );

            assertEquals(
                    true,
                    exchangeQueryStarted.await(
                            10,
                            TimeUnit.SECONDS
                    ),
                    "Первый reconciliation worker должен дойти до exchange query"
            );

            /*
             * Пока первый worker находится на exchange I/O,
             * второй должен уже увидеть RECOVERING ownership
             * и не выполнять второй exchange query.
             */
            releaseExchangeQuery.countDown();

            awaitFuture(
                    worker1
            );

            awaitFuture(
                    worker2
            );

        } finally {

            releaseExchangeQuery.countDown();

            executor.shutdownNow();

            if (!executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            )) {

                fail(
                        "Concurrent reconciliation executor не завершился"
                );
            }
        }

        OrderEntity finalOrder =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order не найден после concurrent recovery"
                                )
                        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                finalOrder.getStatus(),
                "Order должен быть PARTIALLY_FILLED"
        );

        assertBigDecimal(
                "0.3",
                finalOrder.getExecutedQuantity(),
                "executedQuantity после concurrent recovery"
        );

        assertEquals(
                executionId,
                finalOrder.getExecutionId(),
                "executionId не должен измениться"
        );

        RiskStateEntity finalRiskState =
                getRiskState();

        /*
         * Only one cumulative delta 0 -> 0.3 @ 100
         * may be settled.
         */
        assertBigDecimal(
                "10000",
                finalRiskState.getTotalEquity(),
                "totalEquity после concurrent recovery"
        );

        assertBigDecimal(
                "9900",
                finalRiskState.getAvailableBalance(),
                "availableBalance после concurrent recovery"
        );

        assertBigDecimal(
                "70",
                finalRiskState.getReservedMargin(),
                "reservation должна уменьшиться ровно на 30"
        );

        /*
         * The exchange must be queried by exactly one
         * reconciliation worker.
         */
        assertEquals(
                1,
                exchangeQueryCount.get(),
                "Только один reconciliation worker должен выполнить exchange query"
        );
    }

    private RiskStateEntity getRiskState() {

        return riskStateRepository
                .findById(
                        RiskStateEntity.SINGLETON_ID
                )
                .orElseThrow(
                        () -> new AssertionError(
                                "GLOBAL RiskState не найден"
                        )
                );
    }

    private OrderEntity waitForOrder(
            UUID signalId
    ) {

        Instant deadline =
                Instant.now().plusSeconds(15);

        while (Instant.now().isBefore(deadline)) {

            OrderEntity order =
                    orderRepository
                            .findBySignalId(signalId)
                            .orElse(null);

            if (order != null) {
                return order;
            }

            sleep(100);
        }

        fail(
                "Order не создан за 15 секунд. signalId="
                        + signalId
        );

        return null;
    }

    private OrderEntity waitForStatus(
            UUID signalId,
            OrderStatus expectedStatus
    ) {

        Instant deadline =
                Instant.now().plusSeconds(15);

        OrderEntity current = null;

        while (Instant.now().isBefore(deadline)) {

            current =
                    orderRepository
                            .findBySignalId(signalId)
                            .orElse(null);

            if (current != null
                    && current.getStatus() == expectedStatus) {

                return current;
            }

            sleep(100);
        }

        if (current == null) {

            fail(
                    "Order не найден. signalId="
                            + signalId
            );
        }

        fail(
                "Order не перешёл в "
                        + expectedStatus
                        + ". actual="
                        + current.getStatus()
                        + ", orderId="
                        + current.getId()
                        + ", executionId="
                        + current.getExecutionId()
        );

        return null;
    }

    private void drainOutbox() {

        for (int i = 0; i < 20; i++) {

            outboxProcessor.processOutbox();

            boolean pending =
                    outboxRepository
                            .findAll()
                            .stream()
                            .anyMatch(
                                    event ->
                                            event.getStatus()
                                                    != OutboxStatus.PROCESSED
                                                    && event.getStatus()
                                                    != OutboxStatus.DEAD
                            );

            if (!pending) {
                return;
            }

            sleep(100);
        }

        fail(
                "Не удалось полностью обработать outbox"
        );
    }

    private void awaitFuture(
            Future<?> future
    ) throws Exception {

        try {

            future.get(
                    15,
                    TimeUnit.SECONDS
            );

        } catch (ExecutionException e) {

            Throwable cause =
                    e.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw e;
        }
    }

    private void assertBigDecimal(
            String expected,
            BigDecimal actual,
            String message
    ) {

        assertNotNull(
                actual,
                message + ": actual == null"
        );

        assertEquals(
                0,
                actual.compareTo(
                        new BigDecimal(expected)
                ),
                message
                        + ": expected="
                        + expected
                        + ", actual="
                        + actual
        );
    }

    private void sleep(
            long millis
    ) {

        try {

            Thread.sleep(millis);

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            fail(
                    "Test thread interrupted"
            );
        }
    }
}