package com.tradingbot.application;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.position.PositionStatus;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
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
                "app.outbox.enabled=true"
        }
)
@ActiveProfiles("test")
class PartialFillDownstreamIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private SignalExecutionFacade signalExecutionFacade;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @BeforeEach
    void prepareTestEnvironment() {

        when(systemStateManager.isTradingEnabled())
                .thenReturn(true);

        when(systemStateManager.isReady())
                .thenReturn(true);

        /*
         * Сначала удаляем зависимые сущности:
         *
         * TradeEntity -> OrderEntity
         *
         * Поэтому Trade нужно удалить до Order.
         */
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        riskStateRepository.deleteAll();

        riskStateRepository.flush();

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
    void shouldCreateTradeAndPositionAfterPartialFill() {

        UUID signalId =
                UUID.randomUUID();

        String strategyId =
                "PARTIAL-FILL-DOWNSTREAM-TEST";

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
                        strategyId
                );

        /*
         * Реальный application flow:
         *
         * Signal
         * -> Risk
         * -> Reservation
         * -> Order
         * -> ORDER_CREATED
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
         * Биржа отвечает частичным исполнением.
         *
         * Важно:
         * exchangeTradeId должен быть заполнен,
         * иначе OrderExecutedEventHandler не создаст Trade.
         */
        when(
                executionPort.placeOrder(any(Order.class))
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

        /*
         * Процессим:
         *
         * ORDER_CREATED
         * -> claim
         * -> EXECUTING
         * -> ExecutionPort
         * -> PARTIALLY_FILLED
         * -> ORDER_EXECUTED
         */
        drainOutbox();

        OrderEntity partialOrder =
                waitForOrderStatus(
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
                "order.executedQuantity"
        );

        assertNotNull(
                partialOrder.getExecutionId()
        );

        /*
         * Теперь downstream:
         *
         * ORDER_EXECUTED
         * -> OrderExecutedEventHandler
         * -> TradeService
         * -> TRADE_CREATED
         * -> PositionProjectionHandler
         */
        drainOutbox();

        TradeEntity trade =
                waitForTrade(orderId);

        assertNotNull(trade);

        assertNotNull(
                trade.getOrder()
        );

        assertEquals(
                orderId,
                trade.getOrder().getId()
        );

        assertEquals(
                "BTCUSDT",
                trade.getSymbol()
        );

        assertBigDecimal(
                "0.3",
                trade.getQuantity(),
                "trade.quantity"
        );

        assertBigDecimal(
                "100",
                trade.getPrice(),
                "trade.price"
        );

        assertEquals(
                "TEST-EXCHANGE-TRADE-" + orderId,
                trade.getExchangeTradeId()
        );

        assertEquals(
                strategyId,
                trade.getStrategyId()
        );

        /*
         * PositionProjectionHandler должен обработать TRADE_CREATED.
         */
        PositionEntity position =
                waitForPosition(
                        "BTCUSDT",
                        strategyId
                );

        assertNotNull(position);

        assertEquals(
                "BTCUSDT",
                position.getSymbol()
        );

        assertEquals(
                strategyId,
                position.getStrategyId()
        );

        assertBigDecimal(
                "0.3",
                position.getQuantity(),
                "position.quantity"
        );

        assertBigDecimal(
                "100",
                position.getEntryPrice(),
                "position.entryPrice"
        );

        assertEquals(
                "OPEN",
                position.getStatus()
        );
    }

    private OrderEntity waitForOrder(
            UUID signalId
    ) {

        return waitForOrderStatus(
                signalId,
                null
        );
    }

    private OrderEntity waitForOrderStatus(
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

    private TradeEntity waitForTrade(
            UUID orderId
    ) {

        Instant deadline =
                Instant.now()
                        .plus(
                                Duration.ofSeconds(15)
                        );

        while (
                Instant.now().isBefore(deadline)
        ) {

            TradeEntity trade =
                    tradeRepository
                            .findAllByOrderByExecutedAtAsc()
                            .stream()
                            .filter(item ->
                                    item.getOrder() != null
                                            && orderId.equals(
                                            item.getOrder().getId()
                                    )
                            )
                            .findFirst()
                            .orElse(null);

            if (trade != null) {
                return trade;
            }

            sleep(100);
        }

        fail(
                "Trade не создан за 15 секунд. " +
                        "orderId=" + orderId
        );

        return null;
    }

    private PositionEntity waitForPosition(
            String symbol,
            String strategyId
    ) {

        Instant deadline =
                Instant.now()
                        .plus(
                                Duration.ofSeconds(15)
                        );

        while (
                Instant.now().isBefore(deadline)
        ) {

            PositionEntity position =
                    positionRepository
                            .findBySymbolAndStrategyId(
                                    symbol,
                                    strategyId
                            )
                            .orElse(null);

            if (position != null) {
                return position;
            }

            sleep(100);
        }

        fail(
                "Position не создана за 15 секунд. " +
                        "symbol=" + symbol +
                        ", strategyId=" + strategyId
        );

        return null;
    }

    private void drainOutbox() {

        for (int i = 0; i < 20; i++) {

            outboxProcessor.processOutbox();

            boolean hasPending =
                    outboxEventRepository
                            .findAll()
                            .stream()
                            .anyMatch(event ->
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