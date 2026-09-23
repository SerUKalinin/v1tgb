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
class RecoveryCumulativeSettlementIntegrationTest
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
         * Первый execution result:
         *
         * quantity = 0.3
         * price = 100
         *
         * Поэтому из первоначального reservation 100
         * должно быть потреблено 30.
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
    void shouldConsumeOnlyIncrementalNotionalDuringCumulativeRecovery() {

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
                        "RECOVERY-CUMULATIVE-SETTLEMENT-TEST"
                );

        /*
         * ============================================================
         * 1. Создаём Order и первоначальный reservation = 100
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
         * 2. Обрабатываем первый PARTIALLY_FILLED = 0.3
         * ============================================================
         */
        drainOutbox();

        OrderEntity partialOrder =
                waitForStatus(
                        signalId,
                        OrderStatus.PARTIALLY_FILLED
                );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                partialOrder.getStatus()
        );

        assertBigDecimal(
                "0.3",
                partialOrder.getExecutedQuantity(),
                "executedQuantity после initial partial fill"
        );

        RiskStateEntity afterInitialPartial =
                getRiskState();

        /*
         * 100 reservation - 30 consumed = 70.
         */
        assertBigDecimal(
                "9900",
                afterInitialPartial.getAvailableBalance(),
                "availableBalance после initial partial fill"
        );

        assertBigDecimal(
                "70",
                afterInitialPartial.getReservedMargin(),
                "reservedMargin после initial partial fill"
        );

        /*
         * Получаем актуальный domain Order.
         */
        Order partialDomainOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после initial partial fill"
                                )
                        );

        UUID executionId =
                partialDomainOrder.getExecutionId();

        assertNotNull(
                executionId,
                "executionId должен существовать"
        );

        ExecutionContext context =
                ExecutionContext.of(
                        partialDomainOrder
                );

        /*
         * ============================================================
         * 3. Recovery сообщает cumulative executedQty = 0.6
         * ============================================================
         *
         * Очень важно:
         *
         * exchange executedQty = 0.6
         * уже включает предыдущие 0.3.
         *
         * Поэтому новый settlement должен быть:
         *
         *     0.6 - 0.3 = 0.3
         *     0.3 * 100 = 30
         *
         * После этого reservation должен стать 40.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        partialDomainOrder.getSymbol(),
                        partialDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.partiallyFilled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-0.6-" + orderId,
                        "BTCUSDT",
                        partialDomainOrder.getSide(),
                        new BigDecimal("0.6"),
                        new BigDecimal("100"),
                        partialDomainOrder.getClientOrderId()
                )
        );

        reconciliationService.reconcile(
                context
        );

        Order afterRecoveryPartial =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после recovery 0.6"
                                )
                        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                afterRecoveryPartial.getStatus()
        );

        assertBigDecimal(
                "0.6",
                afterRecoveryPartial.getExecutedQuantity(),
                "executedQuantity после recovery 0.6"
        );

        assertEquals(
                executionId,
                afterRecoveryPartial.getExecutionId(),
                "executionId должен оставаться неизменным"
        );

        RiskStateEntity afterRecoveryTo06 =
                getRiskState();

        /*
         * Было 70.
         *
         * Новая delta:
         * 0.6 - 0.3 = 0.3
         * 0.3 * 100 = 30
         *
         * Должно остаться 40.
         */
        assertBigDecimal(
                "9900",
                afterRecoveryTo06.getAvailableBalance(),
                "availableBalance после recovery 0.6"
        );

        assertBigDecimal(
                "40",
                afterRecoveryTo06.getReservedMargin(),
                "reservedMargin после recovery 0.6"
        );

        /*
         * ============================================================
         * 4. Повторный recovery с тем же cumulative executedQty = 0.6
         * ============================================================
         *
         * Повторное получение того же состояния биржи
         * НЕ должно повторно списывать 30.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        partialDomainOrder.getSymbol(),
                        partialDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.partiallyFilled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-0.6-" + orderId,
                        "BTCUSDT",
                        partialDomainOrder.getSide(),
                        new BigDecimal("0.6"),
                        new BigDecimal("100"),
                        partialDomainOrder.getClientOrderId()
                )
        );

        Order currentDomainOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow();

        ExecutionContext repeatedContext =
                ExecutionContext.of(
                        currentDomainOrder
                );

        reconciliationService.reconcile(
                repeatedContext
        );

        RiskStateEntity afterRepeated06 =
                getRiskState();

        /*
         * Должно остаться 40, а не 10.
         */
        assertBigDecimal(
                "9900",
                afterRepeated06.getAvailableBalance(),
                "availableBalance после повторного recovery 0.6"
        );

        assertBigDecimal(
                "40",
                afterRepeated06.getReservedMargin(),
                "reservedMargin после повторного recovery 0.6"
        );

        /*
         * ============================================================
         * 5. Recovery сообщает cumulative executedQty = 1.0
         * ============================================================
         *
         * Новая delta:
         *
         *     1.0 - 0.6 = 0.4
         *     0.4 * 100 = 40
         *
         * Reservation должен стать 0.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        currentDomainOrder.getSymbol(),
                        currentDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-1.0-" + orderId,
                        "BTCUSDT",
                        currentDomainOrder.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        currentDomainOrder.getClientOrderId()
                )
        );

        Order beforeFinalRecovery =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow();

        ExecutionContext finalContext =
                ExecutionContext.of(
                        beforeFinalRecovery
                );

        reconciliationService.reconcile(
                finalContext
        );

        Order finalOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после final recovery"
                                )
                        );

        assertEquals(
                OrderStatus.FILLED,
                finalOrder.getStatus()
        );

        assertBigDecimal(
                "1.0",
                finalOrder.getExecutedQuantity(),
                "executedQuantity после final recovery"
        );

        assertEquals(
                executionId,
                finalOrder.getExecutionId(),
                "executionId должен остаться тем же"
        );

        RiskStateEntity finalRiskState =
                getRiskState();

        /*
         * Все 100 первоначального reservation
         * в итоге должны быть settlement-нуты.
         */
        assertBigDecimal(
                "9900",
                finalRiskState.getAvailableBalance(),
                "availableBalance после полного recovery"
        );

        assertBigDecimal(
                "0",
                finalRiskState.getReservedMargin(),
                "reservedMargin после полного recovery"
        );

        assertBigDecimal(
                "10000",
                finalRiskState.getTotalEquity(),
                "totalEquity после полного recovery"
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