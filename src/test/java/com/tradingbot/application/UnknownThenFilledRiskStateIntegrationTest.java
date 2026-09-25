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
class UnknownThenFilledRiskStateIntegrationTest
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
         * Exchange execution result is deliberately UNKNOWN.
         *
         * Initial reservation = 100.
         *
         * No execution settlement must happen here because
         * the remote result is unresolved.
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
    void shouldRecoverUnknownToFilledAndSettleRemainingReservation() {

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
                        "UNKNOWN-FILLED-RECOVERY-TEST"
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
         * 2. Обрабатываем UNKNOWN execution result
         * ============================================================
         *
         * Важно:
         *
         * executionPort вернул EXCHANGE_STATE_UNKNOWN.
         *
         * Поэтому:
         *
         * PENDING_EXECUTION
         *      ->
         * EXECUTING
         *      ->
         * UNKNOWN
         *
         * Никакого settlement 100 здесь быть не должно.
         */
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

        assertEquals(
                OrderStatus.UNKNOWN,
                unknownOrder.getStatus()
        );

        UUID executionId =
                unknownOrder.getExecutionId();

        assertNotNull(
                executionId,
                "executionId должен сохраняться после UNKNOWN"
        );

        RiskStateEntity afterUnknown =
                getRiskState();

        /*
         * UNKNOWN не означает FILLED.
         *
         * Reservation должна оставаться полностью сохранённой.
         */
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
                "reservedMargin после UNKNOWN"
        );

        /*
         * Получаем актуальный domain Order.
         */
        Order unknownDomainOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после UNKNOWN"
                                )
                        );

        assertEquals(
                OrderStatus.UNKNOWN,
                unknownDomainOrder.getStatus()
        );

        assertEquals(
                executionId,
                unknownDomainOrder.getExecutionId(),
                "executionId не должен измениться при recovery"
        );

        ExecutionContext recoveryContext =
                ExecutionContext.of(
                        unknownDomainOrder
                );

        /*
         * ============================================================
         * 3. Exchange query возвращает authoritative FILLED
         * ============================================================
         *
         * Exchange сообщает:
         *
         * cumulative executedQty = 1.0
         * executedPrice = 100
         *
         * До recovery:
         *
         * executedQty = 0
         *
         * Поэтому incremental notional:
         *
         *     1.0 * 100 - 0 * previousPrice
         *     = 100
         *
         * Из reservation = 100 должно быть consumed = 100.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        unknownDomainOrder.getSymbol(),
                        unknownDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-1.0-" + orderId,
                        "BTCUSDT",
                        unknownDomainOrder.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        unknownDomainOrder.getClientOrderId()
                )
        );

        reconciliationService.reconcile(
                recoveryContext
        );

        /*
         * ============================================================
         * 4. Проверяем authoritative FILLED state
         * ============================================================
         */
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
                "UNKNOWN должен перейти в FILLED через reconciliation"
        );

        assertBigDecimal(
                "1.0",
                filledOrder.getExecutedQuantity(),
                "executedQuantity после FILLED recovery"
        );

        assertEquals(
                0,
                new BigDecimal("100")
                        .compareTo(
                                filledOrder.getAveragePrice()
                        ),
                "averagePrice после FILLED recovery"
        );

        assertEquals(
                executionId,
                filledOrder.getExecutionId(),
                "executionId не должен измениться при recovery"
        );

        /*
         * ============================================================
         * 5. Проверяем RiskState settlement
         * ============================================================
         *
         * Было:
         *
         * available = 9900
         * reserved  = 100
         *
         * После полного authoritative FILLED:
         *
         * available = 9900
         * reserved  = 0
         * totalEquity = 10000
         */
        RiskStateEntity afterFilled =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterFilled.getTotalEquity(),
                "totalEquity после FILLED recovery"
        );

        assertBigDecimal(
                "9900",
                afterFilled.getAvailableBalance(),
                "availableBalance после FILLED recovery"
        );

        assertBigDecimal(
                "0",
                afterFilled.getReservedMargin(),
                "reservedMargin после FILLED recovery"
        );

        /*
         * ============================================================
         * 6. Повторный FILLED recovery
         * ============================================================
         *
         * Terminal state уже FILLED.
         *
         * Повторная reconciliation НЕ должна:
         *
         * - создать новый executionId;
         * - повторно списать reservation;
         * - изменить executedQuantity;
         * - изменить RiskState.
         */
        when(
                exchangeQueryService.getOrderStatus(
                        filledOrder.getSymbol(),
                        filledOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "TEST-EXCHANGE-ORDER-" + orderId,
                        "TEST-EXCHANGE-TRADE-1.0-" + orderId,
                        "BTCUSDT",
                        filledOrder.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        BigDecimal.ZERO,
                        "USDT",
                        filledOrder.getClientOrderId()
                )
        );

        ExecutionContext repeatedRecoveryContext =
                ExecutionContext.of(
                        filledOrder
                );

        reconciliationService.reconcile(
                repeatedRecoveryContext
        );

        Order afterRepeatedRecovery =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow();

        RiskStateEntity afterRepeatedRecoveryRisk =
                getRiskState();

        assertEquals(
                OrderStatus.FILLED,
                afterRepeatedRecovery.getStatus(),
                "FILLED должен остаться terminal"
        );

        assertBigDecimal(
                "1.0",
                afterRepeatedRecovery.getExecutedQuantity(),
                "executedQuantity после повторного FILLED"
        );

        assertEquals(
                executionId,
                afterRepeatedRecovery.getExecutionId(),
                "executionId после повторного FILLED"
        );

        assertBigDecimal(
                "10000",
                afterRepeatedRecoveryRisk.getTotalEquity(),
                "totalEquity после повторного FILLED"
        );

        assertBigDecimal(
                "9900",
                afterRepeatedRecoveryRisk.getAvailableBalance(),
                "availableBalance после повторного FILLED"
        );

        assertBigDecimal(
                "0",
                afterRepeatedRecoveryRisk.getReservedMargin(),
                "reservedMargin после повторного FILLED"
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