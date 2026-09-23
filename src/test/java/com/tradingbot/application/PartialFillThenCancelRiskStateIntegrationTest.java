package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
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
import java.time.Duration;
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
class PartialFillThenCancelRiskStateIntegrationTest
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

        /*
         * BaseIntegrationTest уже создаёт singleton GLOBAL.
         *
         * Не удаляем RiskState и не создаём вторую запись.
         */
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

        /*
         * Этот тест проверяет execution/risk lifecycle,
         * а не реальные Binance symbol constraints.
         *
         * Поэтому exchange feasibility изолируем.
         */
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
         * Execution mock должен быть установлен
         * ДО запуска SignalExecutionFacade.
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
    void shouldReleaseOnlyRemainingReservationAfterPartialFillAndCancel() {

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
                        "PARTIAL-FILL-CANCEL-TEST"
                );

        /*
         * SIGNAL
         *   ->
         * RISK
         *   ->
         * RESERVATION
         *   ->
         * ORDER
         */
        signalExecutionFacade.execute(
                signal
        );

        OrderEntity createdOrder =
                waitForOrder(signalId);

        UUID orderId =
                createdOrder.getId();

        assertNotNull(
                orderId,
                "Order должен быть создан"
        );

        /*
         * Reservation = 100.
         */
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
         * ORDER_CREATED уже должен существовать.
         * Теперь вручную дренируем outbox.
         */
        drainOutbox();

        /*
         * Биржа вернула partial fill.
         */
        OrderEntity partialOrder =
                waitForStatus(
                        signalId,
                        OrderStatus.PARTIALLY_FILLED
                );

        assertEquals(
                orderId,
                partialOrder.getId(),
                "Order ID не должен измениться"
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                partialOrder.getStatus(),
                "Order должен перейти в PARTIALLY_FILLED"
        );

        assertBigDecimal(
                "0.3",
                partialOrder.getExecutedQuantity(),
                "executedQuantity после partial fill"
        );

        BigDecimal remainingQuantity =
                partialOrder
                        .getQuantity()
                        .subtract(
                                partialOrder.getExecutedQuantity()
                        );

        assertBigDecimal(
                "0.7",
                remainingQuantity,
                "remainingQuantity после partial fill"
        );

        /*
         * Reservation должен остаться только
         * на неисполненную часть:
         *
         * 0.7 * 100 = 70
         */
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

        /*
         * Получаем canonical domain Order.
         */
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
         * ExecutionContext строится из того же Order.
         *
         * Это одновременно проверяет identity SSOT,
         * введённый PR #29.
         */
        ExecutionContext context =
                ExecutionContext.of(
                        partialDomainOrder
                );

        /*
         * Теперь биржа сообщает:
         *
         * PARTIALLY_FILLED -> CANCELED
         */
        when(
                exchangeQueryService.getOrderStatus(
                        partialDomainOrder.getClientOrderId()
                )
        ).thenReturn(
                ExecutionResult.canceled(
                        orderId
                )
        );

        /*
         * Запускаем реальную reconciliation ветку.
         */
        reconciliationService.reconcile(
                context
        );

        /*
         * Проверяем состояние Order.
         */
        Order canceledOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после CANCEL"
                                )
                        );

        assertEquals(
                OrderStatus.CANCELED,
                canceledOrder.getStatus(),
                "Order должен перейти PARTIALLY_FILLED -> CANCELED"
        );

        assertBigDecimal(
                "0.3",
                canceledOrder.getExecutedQuantity(),
                "executedQuantity не должен измениться после CANCEL"
        );

        /*
         * Критический identity invariant:
         *
         * CANCEL не создаёт новую execution identity.
         */
        assertEquals(
                executionId,
                canceledOrder.getExecutionId(),
                "executionId не должен измениться после CANCEL"
        );

        /*
         * После CANCEL освобождается только
         * оставшийся резерв:
         *
         * remaining = 0.7
         * reservation = 70
         *
         * 9900 + 70 = 9970
         */
        RiskStateEntity afterCancel =
                getRiskState();

        assertBigDecimal(
                "10000",
                afterCancel.getTotalEquity(),
                "totalEquity после CANCEL"
        );

        assertBigDecimal(
                "9970",
                afterCancel.getAvailableBalance(),
                "availableBalance после CANCEL"
        );

        assertBigDecimal(
                "0",
                afterCancel.getReservedMargin(),
                "reservedMargin после CANCEL"
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
                Instant.now()
                        .plusSeconds(15);

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
                Instant.now()
                        .plusSeconds(15);

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
                "Order не перешёл в " +
                        expectedStatus +
                        ". actual=" +
                        current.getStatus() +
                        ", orderId=" +
                        current.getId() +
                        ", executionId=" +
                        current.getExecutionId()
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
                message +
                        ": expected=" +
                        expected +
                        ", actual=" +
                        actual
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