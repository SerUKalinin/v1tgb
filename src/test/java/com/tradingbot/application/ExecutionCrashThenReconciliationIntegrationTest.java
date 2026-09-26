package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.execution.OrderExecutionCommitService;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.exchange.FeasibilityRequest;
import com.tradingbot.domain.exchange.FeasibilityResult;
import com.tradingbot.domain.exchange.NormalizedOrder;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        properties = {
                "app.outbox.enabled=true",
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("test")
class ExecutionCrashThenReconciliationIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

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

    @SpyBean
    private OrderExecutionCommitService orderExecutionCommitService;

    @BeforeEach
    void prepareTestEnvironment() {

        when(
                systemStateManager.isTradingEnabled()
        ).thenReturn(true);

        when(
                systemStateManager.isReady()
        ).thenReturn(true);

        RiskStateEntity riskState =
                riskStateRepository
                        .findById(
                                RiskStateEntity.SINGLETON_ID
                        )
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "GLOBAL RiskState должен существовать"
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
                new java.util.HashMap<>()
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
         * Exchange submission успешно сообщает FILLED.
         *
         * Commit искусственно падает только на первом
         * обычном execution commit.
         */
        when(
                executionPort.placeOrder(
                        any()
                )
        ).thenAnswer(invocation -> {

            var order =
                    invocation.getArgument(
                            0,
                            com.tradingbot.domain.model.Order.class
                    );

            return ExecutionResult.filled(
                    order.getId(),
                    "EXCHANGE-ORDER-" + order.getId(),
                    "EXCHANGE-TRADE-" + order.getId(),
                    order.getSymbol(),
                    order.getSide(),
                    BigDecimal.ONE,
                    new BigDecimal("100"),
                    BigDecimal.ZERO,
                    "USDT",
                    order.getClientOrderId()
            );
        });

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
    }

    @Test
    void exchangeFillAfterCommitCrashMustBeRecoveredAndSettledExactlyOnce() {

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
                        "EXECUTION-CRASH-RECOVERY-E3"
                );

        signalExecutionFacade.execute(
                signal
        );

        OrderEntity createdOrder =
                waitForOrder(
                        signalId
                );

        UUID orderId =
                createdOrder.getId();

        assertNotNull(
                orderId
        );

        RiskStateEntity afterReservation =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterReservation.getTotalEquity(),
                "totalEquity after reservation"
        );

        assertBigDecimal(
                "9900",
                afterReservation.getAvailableBalance(),
                "availableBalance after reservation"
        );

        assertBigDecimal(
                "100",
                afterReservation.getReservedMargin(),
                "reservedMargin after reservation"
        );

        /*
         * ORDER_CREATED -> real claim -> real exchange submit.
         *
         * Spy throws only from ordinary commit().
         */
        drainOutbox();

        OrderEntity afterCrash =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.EXECUTING,
                afterCrash.getStatus(),
                "claim transaction must remain committed"
        );

        assertEquals(
                1,
                afterCrash.getExecutionAttempts(),
                "exactly one execution attempt must exist"
        );

        UUID executionId =
                afterCrash.getExecutionId();

        assertNotNull(
                executionId
        );

        RiskStateEntity afterCrashRisk =
                getRiskState();

        assertBigDecimal(
                "100",
                afterCrashRisk.getReservedMargin(),
                "reservedMargin after commit crash"
        );

        /*
         * Make EXECUTING stale deterministically.
         */
        OrderEntity staleEntity =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        staleEntity.setExecutionStartedAt(
                Instant.now().minus(
                        Duration.ofMinutes(3)
                )
        );

        staleEntity.setUpdatedAt(
                Instant.now()
        );

        orderRepository.saveAndFlush(
                staleEntity
        );

        when(
                exchangeQueryService.getOrderStatus(
                        "BTCUSDT",
                        staleEntity.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "EXCHANGE-ORDER-" + orderId,
                        "EXCHANGE-TRADE-" + orderId,
                        "BTCUSDT",
                        staleEntity.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        staleEntity.getClientOrderId()
                )
        );

        OrderEntity recoverySource =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        ExecutionContext recoveryContext =
                ExecutionContext.of(
                        com.tradingbot.domain.model.Order
                                .reconstruct(
                                        recoverySource.getId(),
                                        recoverySource.getClientOrderId(),
                                        recoverySource.getSymbol(),
                                        recoverySource.getSide(),
                                        recoverySource.getType(),
                                        recoverySource.getQuantity(),
                                        recoverySource.getPrice(),
                                        recoverySource.getStrategyId(),
                                        recoverySource.getSignalId(),
                                        recoverySource.getStatus(),
                                        recoverySource.getVersion(),
                                        recoverySource.getCreatedAt(),
                                        recoverySource.getUpdatedAt(),
                                        recoverySource.getExecutionId(),
                                        recoverySource.getExecutionStartedAt(),
                                        recoverySource.getExchangeOrderId(),
                                        recoverySource.getExecutedQuantity(),
                                        recoverySource.getAveragePrice(),
                                        recoverySource.getRejectionReason(),
                                        recoverySource.getExecutionAttempts(),
                                        recoverySource.getLastAppliedExecutionId()
                                )
                );

        reconciliationService.reconcile(
                recoveryContext
        );

        OrderEntity recoveredOrder =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        assertEquals(
                OrderStatus.FILLED,
                recoveredOrder.getStatus(),
                "authoritative exchange FILLED must complete lifecycle"
        );

        assertEquals(
                executionId,
                recoveredOrder.getExecutionId(),
                "recovery must preserve executionId"
        );

        assertBigDecimal(
                "1",
                recoveredOrder.getExecutedQuantity(),
                "executedQuantity after recovery"
        );

        assertBigDecimal(
                "100",
                recoveredOrder.getAveragePrice(),
                "averagePrice after recovery"
        );

        RiskStateEntity afterRecovery =
                getRiskState();

        assertBigDecimal(
                "0",
                afterRecovery.getReservedMargin(),
                "reservedMargin after recovery"
        );

        assertBigDecimal(
                "9900",
                afterRecovery.getAvailableBalance(),
                "availableBalance after recovery"
        );

        assertBigDecimal(
                "10000",
                afterRecovery.getTotalEquity(),
                "totalEquity after recovery"
        );

        /*
         * Terminal order is no longer reconcilable.
         */
        OrderEntity finalCurrent =
                orderRepository
                        .findById(orderId)
                        .orElseThrow();

        ExecutionContext repeatedContext =
                ExecutionContext.of(
                        com.tradingbot.domain.model.Order
                                .reconstruct(
                                        finalCurrent.getId(),
                                        finalCurrent.getClientOrderId(),
                                        finalCurrent.getSymbol(),
                                        finalCurrent.getSide(),
                                        finalCurrent.getType(),
                                        finalCurrent.getQuantity(),
                                        finalCurrent.getPrice(),
                                        finalCurrent.getStrategyId(),
                                        finalCurrent.getSignalId(),
                                        finalCurrent.getStatus(),
                                        finalCurrent.getVersion(),
                                        finalCurrent.getCreatedAt(),
                                        finalCurrent.getUpdatedAt(),
                                        finalCurrent.getExecutionId(),
                                        finalCurrent.getExecutionStartedAt(),
                                        finalCurrent.getExchangeOrderId(),
                                        finalCurrent.getExecutedQuantity(),
                                        finalCurrent.getAveragePrice(),
                                        finalCurrent.getRejectionReason(),
                                        finalCurrent.getExecutionAttempts(),
                                        finalCurrent.getLastAppliedExecutionId()
                                )
                );

        reconciliationService.reconcile(
                repeatedContext
        );

        verify(
                exchangeQueryService,
                times(1)
        ).getOrderStatus(
                "BTCUSDT",
                staleEntity.getClientOrderId()
        );

        RiskStateEntity finalRisk =
                getRiskState();

        assertBigDecimal(
                "0",
                finalRisk.getReservedMargin(),
                "reservedMargin after repeated recovery"
        );

        assertBigDecimal(
                "9900",
                finalRisk.getAvailableBalance(),
                "availableBalance after repeated recovery"
        );

        assertBigDecimal(
                "10000",
                finalRisk.getTotalEquity(),
                "totalEquity after repeated recovery"
        );

        verify(
                orderExecutionCommitService,
                times(1)
        ).commit(
                any(),
                any(),
                any(),
                any(),
                any()
        );

        /*
         * Recovery uses commitRecoveredExecution() on the real spy.
         *
         * No second exchange submission is allowed.
         */
        verify(
                executionPort,
                times(1)
        ).placeOrder(
                any()
        );

        verify(
                exchangeQueryService,
                never()
        ).getOrderStatus(
                "BTCUSDT",
                "NON_EXISTENT_CLIENT_ORDER"
        );
    }

    private RiskStateEntity getRiskState() {

        return riskStateRepository
                .findById(
                        RiskStateEntity.SINGLETON_ID
                )
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "GLOBAL RiskState не найден"
                                )
                );
    }

    private OrderEntity waitForOrder(
            UUID signalId
    ) {

        Instant deadline =
                Instant.now()
                        .plusSeconds(15);

        OrderEntity current =
                null;

        while (
                Instant.now().isBefore(deadline)
        ) {

            current =
                    orderRepository
                            .findBySignalId(
                                    signalId
                            )
                            .orElse(null);

            if (current != null) {
                return current;
            }

            sleep(100);
        }

        fail(
                "Order не создан за 15 секунд. signalId="
                        + signalId
        );

        return null;
    }

    private void drainOutbox() {

        for (int i = 0; i < 20; i++) {

            outboxProcessor.processOutbox();

            boolean hasPending =
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

            if (!hasPending) {
                return;
            }

            sleep(100);
        }

        fail(
                "Outbox chain не удалось обработать"
        );
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