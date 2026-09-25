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
class UnknownThenCanceledRiskStateIntegrationTest
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
         * Initial exchange submission is ambiguous:
         *
         * PENDING_EXECUTION
         *      ->
         * EXECUTING
         *      ->
         * UNKNOWN
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
    void shouldRecoverUnknownIntoCanceledAndReleaseReservation() {

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
                        "UNKNOWN-CANCELED-RECOVERY-TEST"
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
                "10000",
                afterUnknown.getTotalEquity(),
                "totalEquity после UNKNOWN"
        );

        assertBigDecimal(
                "9900",
                afterUnknown.getAvailableBalance(),
                "availableBalance после UNKNOWN"
        );

        assertBigDecimal(
                "100",
                afterUnknown.getReservedMargin(),
                "reservation должна сохраняться в UNKNOWN"
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

        when(
                exchangeQueryService.getOrderStatus(
                        unknownDomainOrder.getSymbol(),
                        unknownDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.canceled(
                        orderId
                )
        );

        /*
         * UNKNOWN -> RECOVERING -> CANCELED
         *
         * No additional fill occurred.
         * executedQuantity must remain zero.
         * The complete reservation must be released.
         */
        reconciliationService.reconcile(
                context
        );

        Order canceledOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после CANCELED recovery"
                                )
                        );

        assertEquals(
                OrderStatus.CANCELED,
                canceledOrder.getStatus(),
                "UNKNOWN должен перейти в CANCELED"
        );

        assertBigDecimal(
                "0",
                canceledOrder.getExecutedQuantity(),
                "executedQuantity после CANCELED должен остаться 0"
        );

        assertEquals(
                executionId,
                canceledOrder.getExecutionId(),
                "executionId не должен измениться при CANCELED recovery"
        );

        RiskStateEntity afterCanceled =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterCanceled.getTotalEquity(),
                "totalEquity после CANCELED"
        );

        assertBigDecimal(
                "10000",
                afterCanceled.getAvailableBalance(),
                "availableBalance после CANCELED"
        );

        assertBigDecimal(
                "0",
                afterCanceled.getReservedMargin(),
                "reservedMargin должен быть полностью освобождён"
        );

        /*
         * Repeat the same terminal recovery.
         *
         * CANCELED is terminal.
         * The second reconciliation must be a no-op.
         */
        reconciliationService.reconcile(
                context
        );

        Order repeatedCanceledOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после repeated CANCELED recovery"
                                )
                        );

        assertEquals(
                OrderStatus.CANCELED,
                repeatedCanceledOrder.getStatus(),
                "Повторный CANCELED должен оставить CANCELED"
        );

        assertBigDecimal(
                "0",
                repeatedCanceledOrder.getExecutedQuantity(),
                "executedQuantity не должен измениться повторно"
        );

        assertEquals(
                executionId,
                repeatedCanceledOrder.getExecutionId(),
                "executionId не должен измениться после repeated CANCELED"
        );

        RiskStateEntity afterRepeatedCanceled =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterRepeatedCanceled.getTotalEquity(),
                "totalEquity после repeated CANCELED"
        );

        assertBigDecimal(
                "10000",
                afterRepeatedCanceled.getAvailableBalance(),
                "availableBalance не должен измениться после repeated CANCELED"
        );

        assertBigDecimal(
                "0",
                afterRepeatedCanceled.getReservedMargin(),
                "reservedMargin не должен измениться после repeated CANCELED"
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