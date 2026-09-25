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
class UnknownThenPartialFillRiskStateIntegrationTest
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
                        .findById(RiskStateEntity.SINGLETON_ID)
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
         * Initial execution result is UNKNOWN.
         *
         * This establishes:
         * PENDING_EXECUTION -> EXECUTING -> UNKNOWN
         *
         * without performing any settlement.
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
    void shouldRecoverUnknownIntoPartialFillAndSettleOnlyIncrementalNotional() {

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
                        "UNKNOWN-PARTIAL-RECOVERY-TEST"
                );

        signalExecutionFacade.execute(signal);

        OrderEntity createdOrder =
                waitForOrder(signalId);

        UUID orderId =
                createdOrder.getId();

        assertNotNull(
                orderId,
                "Order должен быть создан"
        );

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

        assertEquals(
                orderId,
                unknownOrder.getId()
        );

        UUID executionId =
                unknownOrder.getExecutionId();

        assertNotNull(
                executionId,
                "executionId должен существовать в UNKNOWN"
        );

        assertBigDecimal(
                "0",
                unknownOrder.getExecutedQuantity(),
                "executedQuantity в UNKNOWN"
        );

        RiskStateEntity afterUnknown =
                getRiskState();

        assertBigDecimal(
                "9900",
                afterUnknown.getAvailableBalance(),
                "availableBalance после UNKNOWN"
        );

        assertBigDecimal(
                "100",
                afterUnknown.getReservedMargin(),
                "reservedMargin после UNKNOWN"
        );

        Order unknownDomainOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден в UNKNOWN"
                                )
                        );

        ExecutionContext recoveryContext =
                ExecutionContext.of(
                        unknownDomainOrder
                );

        when(
                exchangeQueryService.getOrderStatus(
                        unknownDomainOrder.getSymbol(),
                        unknownDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.partiallyFilled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-0.3-" + orderId,
                        "BTCUSDT",
                        unknownDomainOrder.getSide(),
                        new BigDecimal("0.3"),
                        new BigDecimal("100"),
                        unknownDomainOrder.getClientOrderId()
                )
        );

        /*
         * UNKNOWN -> RECOVERING -> PARTIALLY_FILLED
         *
         * Incremental notional:
         *
         * previous = 0
         * current  = 0.3 * 100 = 30
         * delta    = 30
         *
         * Remaining reservation:
         * 100 - 30 = 70
         */
        reconciliationService.reconcile(
                recoveryContext
        );

        Order partialOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после PARTIAL recovery"
                                )
                        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                partialOrder.getStatus(),
                "UNKNOWN должен перейти в PARTIALLY_FILLED"
        );

        assertBigDecimal(
                "0.3",
                partialOrder.getExecutedQuantity(),
                "executedQuantity после PARTIAL recovery"
        );

        assertBigDecimal(
                "100",
                partialOrder.getAveragePrice(),
                "averagePrice после PARTIAL recovery"
        );

        assertEquals(
                executionId,
                partialOrder.getExecutionId(),
                "executionId не должен измениться при recovery"
        );

        RiskStateEntity afterPartialRecovery =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterPartialRecovery.getTotalEquity(),
                "totalEquity после PARTIAL recovery"
        );

        assertBigDecimal(
                "9900",
                afterPartialRecovery.getAvailableBalance(),
                "availableBalance после PARTIAL recovery"
        );

        assertBigDecimal(
                "70",
                afterPartialRecovery.getReservedMargin(),
                "reservedMargin после PARTIAL recovery"
        );

        /*
         * Repeat exactly the same exchange snapshot.
         *
         * Expected:
         * previous notional = 30
         * current notional  = 30
         * delta             = 0
         *
         * Therefore risk state must remain unchanged.
         */
        reconciliationService.reconcile(
                recoveryContext
        );

        Order repeatedPartialOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после repeated PARTIAL recovery"
                                )
                        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                repeatedPartialOrder.getStatus(),
                "Повторный PARTIAL должен оставить PARTIALLY_FILLED"
        );

        assertBigDecimal(
                "0.3",
                repeatedPartialOrder.getExecutedQuantity(),
                "executedQuantity не должен увеличиться повторно"
        );

        assertBigDecimal(
                "100",
                repeatedPartialOrder.getAveragePrice(),
                "averagePrice не должен измениться повторно"
        );

        assertEquals(
                executionId,
                repeatedPartialOrder.getExecutionId(),
                "executionId не должен измениться после повторного recovery"
        );

        RiskStateEntity afterRepeatedPartial =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterRepeatedPartial.getTotalEquity(),
                "totalEquity после repeated PARTIAL recovery"
        );

        assertBigDecimal(
                "9900",
                afterRepeatedPartial.getAvailableBalance(),
                "availableBalance после repeated PARTIAL recovery"
        );

        assertBigDecimal(
                "70",
                afterRepeatedPartial.getReservedMargin(),
                "reservedMargin не должен уменьшиться повторно"
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

    private void sleep(long millis) {

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