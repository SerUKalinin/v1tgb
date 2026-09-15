package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import com.tradingbot.domain.exchange.ExecutionPort;
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
    private TradingSystemBootstrapper tradingSystemBootstrapper;

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

        riskStateRepository.deleteAll();

        RiskStateEntity riskState =
                new RiskStateEntity();

        riskState.setId(
                RiskStateEntity.SINGLETON_ID
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

        riskState.setVersion(0L);

        riskState.setUpdatedAt(
                Instant.now()
        );

        riskStateRepository.saveAndFlush(
                riskState
        );
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
         * 1. Production path:
         * SIGNAL -> RISK -> RESERVATION -> ORDER_CREATED
         */
        signalExecutionFacade.execute(
                signal
        );

        OrderEntity createdOrder =
                waitForOrder(signalId);

        UUID orderId =
                createdOrder.getId();

        assertNotNull(orderId);

        /*
         * После reservation:
         *
         * totalEquity      = 10000
         * availableBalance = 9900
         * reservedMargin   = 100
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
         * 2. Биржа возвращает PARTIALLY_FILLED:
         *
         * original quantity = 1.0
         * executed quantity = 0.3
         * remaining         = 0.7
         */
        when(
                executionPort.placeOrder(any())
        ).thenAnswer(invocation -> {

            var order =
                    invocation.getArgument(
                            0,
                            com.tradingbot.domain.model.Order.class
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

        drainOutbox();

        OrderEntity partialOrder =
                waitForStatus(
                        signalId,
                        OrderStatus.PARTIALLY_FILLED
                );

        assertEquals(
                orderId,
                partialOrder.getId()
        );

        assertEquals(
                OrderStatus.PARTIALLY_FILLED,
                partialOrder.getStatus()
        );

        assertBigDecimal(
                "0.3",
                partialOrder.getExecutedQuantity(),
                "executedQuantity после partial fill"
        );

        BigDecimal remainingQuantity =
                partialOrder.getQuantity()
                        .subtract(
                                partialOrder.getExecutedQuantity()
                        );

        assertBigDecimal(
                "0.7",
                remainingQuantity,
                "remainingQuantity после partial fill"
        );

        /*
         * После partial fill reservation:
         *
         * availableBalance = 9900
         * reservedMargin   = 70
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
         * executionId должен остаться тем же самым
         * при переходе через reconciliation.
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
                "executionId должен существовать после partial fill"
        );

        ExecutionContext context =
                ExecutionContext.of(
                        partialDomainOrder
                );

        /*
         * 3. Симулируем реальное состояние биржи:
         *
         * ордер был частично исполнен и затем отменён.
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
         * 4. Запускаем настоящую reconciliation-ветку:
         *
         * PARTIALLY_FILLED
         *      ->
         * CANCELED
         *      +
         * releasePartial()
         */
        reconciliationService.reconcile(
                context
        );

        /*
         * 5. Проверяем состояние Order.
         */
        Order canceledOrder =
                orderRepositoryPort
                        .findById(orderId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Domain Order не найден после CANCEL reconciliation"
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

        assertEquals(
                executionId,
                canceledOrder.getExecutionId(),
                "executionId не должен измениться после CANCEL"
        );

        /*
         * 6. Главное финансовое утверждение.
         *
         * Было:
         * balance = 9900
         * reserve = 70
         *
         * Освобождаем только remaining = 0.7 * 100 = 70.
         *
         * Должно стать:
         * balance = 9970
         * reserve = 0
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
                                "RiskState должен существовать"
                        )
                );
    }

    private OrderEntity waitForOrder(
            UUID signalId
    ) {

        return waitForStatus(
                signalId,
                null
        );
    }

    private OrderEntity waitForStatus(
            UUID signalId,
            OrderStatus expectedStatus
    ) {

        Instant deadline =
                Instant.now()
                        .plus(
                                Duration.ofSeconds(15)
                        );

        OrderEntity current = null;

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

                if (expectedStatus == null) {
                    return current;
                }

                if (current.getStatus() == expectedStatus) {
                    return current;
                }
            }

            sleep(100);
        }

        if (current == null) {

            fail(
                    "Order не создан за 15 секунд. " +
                            "signalId=" + signalId
            );
        }

        fail(
                "Order не перешёл в ожидаемый статус за 15 секунд. " +
                        "expected=" + expectedStatus +
                        ", actual=" + current.getStatus() +
                        ", orderId=" + current.getId() +
                        ", executionId=" + current.getExecutionId()
        );

        return current;
    }

    private void drainOutbox() {

        for (int i = 0; i < 10; i++) {

            outboxProcessor.processOutbox();

            boolean hasPendingEvents =
                    outboxRepository
                            .findAll()
                            .stream()
                            .anyMatch(
                                    event ->
                                            event.getStatus()
                                                    != com.tradingbot.infrastructure.outbox.OutboxStatus.PROCESSED
                                                    && event.getStatus()
                                                    != com.tradingbot.infrastructure.outbox.OutboxStatus.DEAD
                            );

            if (!hasPendingEvents) {
                return;
            }
        }

        fail(
                "Outbox chain не удалось полностью обработать за 10 проходов"
        );
    }

    private void assertBigDecimal(
            String expected,
            BigDecimal actual,
            String message
    ) {

        assertNotNull(
                actual,
                message + ": значение null"
        );

        assertEquals(
                0,
                actual.compareTo(
                        new BigDecimal(expected)
                ),
                message +
                        ": expected=" + expected +
                        ", actual=" + actual
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
                    "Test thread был прерван"
            );
        }
    }
}