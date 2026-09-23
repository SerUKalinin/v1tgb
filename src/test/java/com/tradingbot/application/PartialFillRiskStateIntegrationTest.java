package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.exchange.SymbolConstraints;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.execution.exchange.ExchangeMetadataService;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
class PartialFillRiskStateIntegrationTest
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

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @MockBean
    private ExchangeMetadataService metadataService;

    @BeforeEach
    void prepareTestEnvironment() {

        when(systemStateManager.isTradingEnabled())
                .thenReturn(true);

        when(systemStateManager.isReady())
                .thenReturn(true);

        when(
                metadataService.getConstraints("BTCUSDT")
        ).thenReturn(
                Optional.of(
                        SymbolConstraints.builder()
                                .symbol("BTCUSDT")
                                .stepSize(new BigDecimal("0.00001"))
                                .minQty(new BigDecimal("0.00001"))
                                .tickSize(new BigDecimal("0.01"))
                                .minNotional(new BigDecimal("5"))
                                .build()
                )
        );

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

        riskState.setUpdatedAt(
                Instant.now()
        );

        riskStateRepository.saveAndFlush(
                riskState
        );
    }

    @Test
    void shouldKeepOnlyRemainingReservationAfterPartialFill() {

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
                        "PARTIAL-FILL-TEST"
                );

        /*
         * ExecutionPort должен быть настроен до запуска pipeline.
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

        signalExecutionFacade.execute(
                signal
        );

        OrderEntity createdOrder =
                waitForOrder(signalId);

        UUID orderId =
                createdOrder.getId();

        assertNotNull(orderId);

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
                "executedQuantity"
        );

        BigDecimal remainingQuantity =
                partialOrder.getQuantity()
                        .subtract(
                                partialOrder.getExecutedQuantity()
                        );

        assertBigDecimal(
                "0.7",
                remainingQuantity,
                "remainingQuantity"
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
                "Outbox chain не удалось полностью обработать за 20 проходов"
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