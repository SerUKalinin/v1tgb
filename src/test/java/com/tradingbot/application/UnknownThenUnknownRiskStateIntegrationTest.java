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
class UnknownThenUnknownRiskStateIntegrationTest
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
    void shouldKeepUnknownAndPreserveRiskStateWhenExchangeRemainsAmbiguous() {

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
                        "UNKNOWN-UNKNOWN-RECOVERY-TEST"
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

        RiskStateEntity afterInitialUnknown =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterInitialUnknown.getTotalEquity(),
                "totalEquity после UNKNOWN"
        );

        assertBigDecimal(
                "9900",
                afterInitialUnknown.getAvailableBalance(),
                "availableBalance после UNKNOWN"
        );

        assertBigDecimal(
                "100",
                afterInitialUnknown.getReservedMargin(),
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

        /*
         * Exchange is still ambiguous.
         *
         * Recovery must remain UNKNOWN.
         * No fill => no settlement delta.
         * Reservation must remain untouched.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        unknownDomainOrder.getSymbol(),
                        unknownDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.exchangeStateUnknown(
                        orderId
                )
        );

        reconciliationService.reconcile(
                context
        );

        Order stillUnknown =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после UNKNOWN recovery"
                                )
                        );

        assertEquals(
                OrderStatus.UNKNOWN,
                stillUnknown.getStatus(),
                "UNKNOWN должен остаться UNKNOWN"
        );

        assertBigDecimal(
                "0",
                stillUnknown.getExecutedQuantity(),
                "executedQuantity не должен измениться"
        );

        assertEquals(
                executionId,
                stillUnknown.getExecutionId(),
                "executionId не должен измениться"
        );

        RiskStateEntity afterUnknownRecovery =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterUnknownRecovery.getTotalEquity(),
                "totalEquity не должен измениться"
        );

        assertBigDecimal(
                "9900",
                afterUnknownRecovery.getAvailableBalance(),
                "availableBalance не должен измениться"
        );

        assertBigDecimal(
                "100",
                afterUnknownRecovery.getReservedMargin(),
                "reservation должна оставаться 100"
        );

        /*
         * Second ambiguous recovery.
         *
         * This verifies repeated UNKNOWN is also deterministic:
         * no cumulative settlement, no execution identity rotation,
         * no release of the reservation.
         */
        reconciliationService.reconcile(
                context
        );

        Order repeatedUnknown =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после repeated UNKNOWN recovery"
                                )
                        );

        assertEquals(
                OrderStatus.UNKNOWN,
                repeatedUnknown.getStatus(),
                "Повторный UNKNOWN должен оставить UNKNOWN"
        );

        assertBigDecimal(
                "0",
                repeatedUnknown.getExecutedQuantity(),
                "executedQuantity не должен измениться после repeated UNKNOWN"
        );

        assertEquals(
                executionId,
                repeatedUnknown.getExecutionId(),
                "executionId не должен измениться после repeated UNKNOWN"
        );

        RiskStateEntity afterRepeatedUnknown =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterRepeatedUnknown.getTotalEquity(),
                "totalEquity не должен измениться после repeated UNKNOWN"
        );

        assertBigDecimal(
                "9900",
                afterRepeatedUnknown.getAvailableBalance(),
                "availableBalance не должен измениться после repeated UNKNOWN"
        );

        assertBigDecimal(
                "100",
                afterRepeatedUnknown.getReservedMargin(),
                "reservation не должна измениться после repeated UNKNOWN"
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