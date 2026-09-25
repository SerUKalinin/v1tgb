package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
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
class PartialFillThenFilledRecoveryIntegrationTest
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
         * Initial execution:
         *
         * 1.0 requested
         * 0.3 executed @ 100
         *
         * Initial reservation = 100.
         * Initial settlement = 30.
         * Remaining reservation = 70.
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

            return ExecutionResult.partiallyFilled(
                    order.getId(),
                    "TEST-EXCHANGE-ORDER-" + order.getId(),
                    "TEST-EXCHANGE-TRADE-" + order.getId(),
                    order.getSymbol(),
                    order.getSide(),
                    new BigDecimal("0.3"),
                    new BigDecimal("100"),
                    order.getClientOrderId()
            );
        });
    }

    @Test
    void shouldSettleOnlyRemainingNotionalDuringFinalFillRecovery() {

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
                        "PARTIAL-FILL-FILLED-RECOVERY-TEST"
                );

        /*
         * ============================================================
         * 1. Создаём Order.
         *
         * Первоначальная reservation:
         *
         *     1.0 * 100 = 100
         * ============================================================
         */
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

        /*
         * ============================================================
         * 2. Initial PARTIALLY_FILLED = 0.3 @ 100
         *
         * Settlement:
         *
         *     0.3 * 100 = 30
         *
         * Remaining reservation:
         *
         *     100 - 30 = 70
         * ============================================================
         */
        drainOutbox();

        OrderEntity partialOrder =
                waitForStatus(
                        signalId,
                        OrderStatus.PARTIALLY_FILLED
                );

        assertBigDecimal(
                "0.3",
                partialOrder.getExecutedQuantity(),
                "executedQuantity после initial partial fill"
        );

        RiskStateEntity afterPartialFill =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterPartialFill.getTotalEquity(),
                "totalEquity после partial fill"
        );

        assertBigDecimal(
                "9900",
                afterPartialFill.getAvailableBalance(),
                "availableBalance после partial fill"
        );

        assertBigDecimal(
                "70",
                afterPartialFill.getReservedMargin(),
                "reservedMargin после partial fill"
        );

        Order partialDomainOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после partial fill"
                                )
                        );

        UUID executionId =
                partialDomainOrder.getExecutionId();

        assertNotNull(
                executionId,
                "executionId должен существовать"
        );

        /*
         * ============================================================
         * 3. Recovery: FILLED = 1.0 @ 100
         *
         * Exchange сообщает CUMULATIVE state:
         *
         *     current = 1.0 * 100 = 100
         *
         * Previous:
         *
         *     previous = 0.3 * 100 = 30
         *
         * Поэтому settlement должен быть только:
         *
         *     delta = 100 - 30 = 70
         *
         * НЕ 100.
         *
         * Reservation:
         *
         *     70 - 70 = 0
         * ============================================================
         */
        when(
                exchangeQueryService.getOrderStatus(
                        partialDomainOrder.getSymbol(),
                        partialDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-1.0-" + orderId,
                        "BTCUSDT",
                        partialDomainOrder.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        partialDomainOrder.getClientOrderId()
                )
        );

        ExecutionContext finalContext =
                ExecutionContext.of(
                        partialDomainOrder
                );

        reconciliationService.reconcile(
                finalContext
        );

        Order filledOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после FILLED recovery"
                                )
                        );

        assertEquals(
                OrderStatus.FILLED,
                filledOrder.getStatus(),
                "Order должен перейти PARTIALLY_FILLED -> FILLED"
        );

        assertBigDecimal(
                "1.0",
                filledOrder.getExecutedQuantity(),
                "executedQuantity после FILLED recovery"
        );

        assertEquals(
                executionId,
                filledOrder.getExecutionId(),
                "executionId не должен измениться после FILLED recovery"
        );

        RiskStateEntity afterFinalFill =
                getRiskState();

        /*
         * Должно быть:
         *
         * totalEquity      = 10000
         * availableBalance = 9900
         * reservedMargin   = 0
         */
        assertBigDecimal(
                "10000",
                afterFinalFill.getTotalEquity(),
                "totalEquity после FILLED recovery"
        );

        assertBigDecimal(
                "9900",
                afterFinalFill.getAvailableBalance(),
                "availableBalance после FILLED recovery"
        );

        assertBigDecimal(
                "0",
                afterFinalFill.getReservedMargin(),
                "reservedMargin после FILLED recovery"
        );

        /*
         * ============================================================
         * 4. Повторный FILLED recovery.
         *
         * То же самое cumulative exchange state:
         *
         *     1.0 @ 100
         *
         * Уже весь notional settlement-нется.
         * Повторно списывать 100 или 70 нельзя.
         *
         * Terminal FILLED должен остаться неизменным.
         * ============================================================
         */
        Order currentFilledOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow();

        ExecutionContext repeatedContext =
                ExecutionContext.of(
                        currentFilledOrder
                );

        when(
                exchangeQueryService.getOrderStatus(
                        currentFilledOrder.getSymbol(),
                        currentFilledOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-1.0-" + orderId,
                        "BTCUSDT",
                        currentFilledOrder.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        currentFilledOrder.getClientOrderId()
                )
        );

        reconciliationService.reconcile(
                repeatedContext
        );

        Order afterRepeatedFilledRecovery =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после repeated FILLED recovery"
                                )
                        );

        assertEquals(
                OrderStatus.FILLED,
                afterRepeatedFilledRecovery.getStatus(),
                "Повторный FILLED не должен менять terminal status"
        );

        assertBigDecimal(
                "1.0",
                afterRepeatedFilledRecovery.getExecutedQuantity(),
                "executedQuantity не должен измениться после repeated FILLED"
        );

        assertEquals(
                executionId,
                afterRepeatedFilledRecovery.getExecutionId(),
                "executionId не должен измениться после repeated FILLED"
        );

        RiskStateEntity afterRepeatedFillRisk =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterRepeatedFillRisk.getTotalEquity(),
                "totalEquity не должен измениться после repeated FILLED"
        );

        assertBigDecimal(
                "9900",
                afterRepeatedFillRisk.getAvailableBalance(),
                "availableBalance не должен измениться после repeated FILLED"
        );

        assertBigDecimal(
                "0",
                afterRepeatedFillRisk.getReservedMargin(),
                "reservedMargin не должен измениться после repeated FILLED"
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