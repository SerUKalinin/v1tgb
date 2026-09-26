package com.tradingbot.application;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.infrastructure.execution.exchange.ExchangeMetadataService;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(
        properties = {
                "app.outbox.enabled=true",
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("test")
class PartialFillCumulativeDownstreamIntegrationTest {

    private static final String SYMBOL = "BTCUSDT";

    private static final String STRATEGY_ID =
            "PARTIAL-FILL-CUMULATIVE-DOWNSTREAM-TEST";

    private static final UUID SIGNAL_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORDER_ID =
            IdentityFactory.deriveOrder(
                    SIGNAL_ID
            );

    private static final ExecutionAttemptContext ATTEMPT =
            ExecutionAttemptContext.firstAttemptForOrder(
                    ORDER_ID
            );

    private static final UUID EXECUTION_ID =
            ATTEMPT.executionId();

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
    private EquitySnapshotRepository equitySnapshotRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @MockBean
    private ExchangeMetadataService metadataService;

    @BeforeEach
    void setUp() {

        /*
         * Полная изоляция теста.
         */
        equitySnapshotRepository.deleteAll();
        tradeRepository.deleteAll();
        positionRepository.deleteAll();
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        riskStateRepository.deleteAll();

        equitySnapshotRepository.flush();
        tradeRepository.flush();
        positionRepository.flush();
        outboxEventRepository.flush();
        orderRepository.flush();
        riskStateRepository.flush();

        configureSystemState();

        seedRiskState();

        createOrder();
    }

    @Test
    void shouldPropagateCumulativePartialFillDownstreamExactlyOnce() {

        /*
         * ============================================================
         * CHECKPOINT #1
         *
         * PARTIALLY_FILLED
         * cumulative quantity = 0.3
         * cumulative price    = 100
         * ============================================================
         */

        updateOrderState(
                OrderStatus.PARTIALLY_FILLED,
                "0.3",
                "100"
        );

        UUID checkpoint1EventId =
                publishOrderExecutedCheckpoint(
                        OrderStatus.PARTIALLY_FILLED,
                        "0.3",
                        "100",
                        "EXCHANGE-TRADE-1"
                );

        assertNotNull(checkpoint1EventId);

        assertEquals(
                OutboxStatus.NEW,
                outboxEventRepository
                        .findById(checkpoint1EventId)
                        .orElseThrow()
                        .getStatus()
        );

        drainOutbox();

        /*
         * После первого cumulative checkpoint:
         *
         * Trade    = 0.3
         * Position = 0.3
         * Equity   = 1 snapshot
         */
        assertTradeState(
                1,
                "0.3"
        );

        assertPositionQuantity(
                "0.3"
        );

        assertEquals(
                1,
                equitySnapshotRepository.count()
        );

        /*
         * ============================================================
         * CHECKPOINT #2
         *
         * PARTIALLY_FILLED
         * cumulative quantity = 0.6
         * cumulative price    = 100
         *
         * delta = 0.6 - 0.3 = 0.3
         * ============================================================
         */

        updateOrderState(
                OrderStatus.PARTIALLY_FILLED,
                "0.6",
                "100"
        );

        UUID checkpoint2EventId =
                publishOrderExecutedCheckpoint(
                        OrderStatus.PARTIALLY_FILLED,
                        "0.6",
                        "100",
                        "EXCHANGE-TRADE-2"
                );

        assertNotNull(checkpoint2EventId);

        drainOutbox();

        /*
         * Один lifecycle -> один Trade.
         * Trade хранит cumulative quantity.
         */
        assertTradeState(
                1,
                "0.6"
        );

        /*
         * Position получает только delta +0.3:
         *
         * 0.3 + 0.3 = 0.6
         *
         * Если бы downstream применил cumulative 0.6,
         * получилось бы 0.9 — это дефект, который ловит тест.
         */
        assertPositionQuantity(
                "0.6"
        );

        assertEquals(
                2,
                equitySnapshotRepository.count()
        );

        /*
         * ============================================================
         * DUPLICATE CHECKPOINT #2
         *
         * Тот же самый eventId.
         * Новая outbox запись НЕ создаётся.
         * ============================================================
         */

        requeueProcessedOutboxEvent(
                checkpoint2EventId
        );

        drainOutbox();

        /*
         * Ничего не должно измениться.
         */
        assertTradeState(
                1,
                "0.6"
        );

        assertPositionQuantity(
                "0.6"
        );

        assertEquals(
                2,
                equitySnapshotRepository.count()
        );

        /*
         * ============================================================
         * CHECKPOINT #3
         *
         * FILLED
         * cumulative quantity = 1.0
         * cumulative price    = 100
         *
         * delta = 1.0 - 0.6 = 0.4
         * ============================================================
         */

        updateOrderState(
                OrderStatus.FILLED,
                "1.0",
                "100"
        );

        UUID checkpoint3EventId =
                publishOrderExecutedCheckpoint(
                        OrderStatus.FILLED,
                        "1.0",
                        "100",
                        "EXCHANGE-TRADE-3"
                );

        assertNotNull(checkpoint3EventId);

        drainOutbox();

        /*
         * Один Trade на весь execution lifecycle.
         */
        assertTradeState(
                1,
                "1.0"
        );

        /*
         * Position:
         *
         * +0.3
         * +0.3
         * +0.4
         * ------
         *  1.0
         */
        assertPositionQuantity(
                "1.0"
        );

        /*
         * Ровно один EquitySnapshot на каждый
         * новый cumulative checkpoint.
         */
        assertEquals(
                3,
                equitySnapshotRepository.count()
        );

        assertEquals(
                1,
                tradeRepository.count()
        );
    }

    private UUID publishOrderExecutedCheckpoint(
            OrderStatus status,
            String cumulativeQuantity,
            String cumulativePrice,
            String exchangeTradeId
    ) {

        BigDecimal quantity =
                new BigDecimal(
                        cumulativeQuantity
                );

        BigDecimal price =
                new BigDecimal(
                        cumulativePrice
                );

        String checkpointKey =
                status.name()
                        + ":"
                        + normalize(quantity)
                        + "@"
                        + normalize(price);

        /*
         * КАНОНИЧЕСКАЯ IDENTITY-СХЕМА:
         *
         * executionId
         *      +
         * checkpoint state
         *      ↓
         * checkpoint eventType
         *      ↓
         * eventId
         *
         * Никаких UUID.randomUUID() и самодельных eventId.
         */
        String eventType =
                IdentityFactory.deriveCheckpointEventType(
                        EXECUTION_ID,
                        "ORDER_EXECUTED",
                        checkpointKey
                );

        ExecutionContext context =
                ExecutionContext.of(
                        IdentityContext.of(
                                SIGNAL_ID
                        ),
                        ATTEMPT,
                        BusinessContext.of(
                                ORDER_ID.toString()
                        )
                );

        OrderExecutedEvent payload =
                OrderExecutedEvent.builder()
                        .identity(
                                context.identity()
                        )
                        .attempt(
                                context.attempt()
                        )
                        .business(
                                context.business()
                        )
                        .orderId(
                                ORDER_ID
                        )
                        .symbol(
                                SYMBOL
                        )
                        .quantity(
                                quantity
                        )
                        .price(
                                price
                        )
                        .status(
                                status
                        )
                        .rejectionReason(
                                null
                        )
                        .exchangeTradeId(
                                exchangeTradeId
                        )
                        .timestamp(
                                Instant.now()
                        )
                        .build();

        UUID eventId =
                IdentityFactory.deriveEventId(
                        EXECUTION_ID,
                        eventType
                );

        /*
         * Используем штатный OutboxService.
         */
        outboxService.publishEvent(
                context,
                "ORDER",
                eventType,
                payload
        );

        outboxEventRepository.flush();

        return eventId;
    }

    private void requeueProcessedOutboxEvent(
            UUID eventId
    ) {

        var event =
                outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Outbox event не найден: " +
                                                eventId
                                )
                        );

        assertEquals(
                OutboxStatus.PROCESSED,
                event.getStatus()
        );

        /*
         * Это повторная доставка ТОЙ ЖЕ записи.
         *
         * eventId остаётся абсолютно тем же.
         */
        event.setStatus(
                OutboxStatus.NEW
        );

        event.setProcessedAt(
                null
        );

        event.setLastError(
                null
        );

        event.setLockedUntil(
                null
        );

        event.setLockOwner(
                null
        );

        event.setClaimedBy(
                null
        );

        event.setClaimedAt(
                null
        );

        event.setLeaseUntil(
                null
        );

        outboxEventRepository.saveAndFlush(
                event
        );
    }

    private void createOrder() {

        OrderEntity order =
                OrderEntity.builder()
                        .id(
                                ORDER_ID
                        )
                        .clientOrderId(
                                "CLIENT-" + ORDER_ID
                        )
                        .symbol(
                                SYMBOL
                        )
                        .side(
                                OrderSide.BUY
                        )
                        .type(
                                OrderType.MARKET
                        )
                        .quantity(
                                new BigDecimal("1.0")
                        )
                        .price(
                                new BigDecimal("100")
                        )
                        .strategyId(
                                STRATEGY_ID
                        )
                        .signalId(
                                SIGNAL_ID
                        )
                        .status(
                                OrderStatus.PARTIALLY_FILLED
                        )
                        .executedQuantity(
                                new BigDecimal("0.3")
                        )
                        .averagePrice(
                                new BigDecimal("100")
                        )
                        .executionId(
                                EXECUTION_ID
                        )
                        .executionAttempts(
                                1
                        )
                        .createdAt(
                                Instant.now()
                        )
                        .build();

        orderRepository.saveAndFlush(
                order
        );
    }

    private void updateOrderState(
            OrderStatus status,
            String executedQuantity,
            String averagePrice
    ) {

        OrderEntity order =
                orderRepository
                        .findById(
                                ORDER_ID
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order не найден: " +
                                                ORDER_ID
                                )
                        );

        order.setStatus(
                status
        );

        order.setExecutedQuantity(
                new BigDecimal(
                        executedQuantity
                )
        );

        order.setAveragePrice(
                new BigDecimal(
                        averagePrice
                )
        );

        order.setExecutionId(
                EXECUTION_ID
        );

        orderRepository.saveAndFlush(
                order
        );
    }

    private void assertTradeState(
            long expectedCount,
            String expectedQuantity
    ) {

        List<TradeEntity> trades =
                tradeRepository.findAll();

        assertEquals(
                expectedCount,
                trades.size(),
                "Неверное количество Trade"
        );

        if (expectedCount == 0) {
            return;
        }

        TradeEntity trade =
                trades.stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Trade отсутствует"
                                )
                        );

        assertNotNull(
                trade.getOrder()
        );

        assertEquals(
                ORDER_ID,
                trade.getOrder().getId()
        );

        assertEquals(
                SYMBOL,
                trade.getSymbol()
        );

        assertEquals(
                STRATEGY_ID,
                trade.getStrategyId()
        );

        assertBigDecimal(
                expectedQuantity,
                trade.getQuantity(),
                "trade.quantity"
        );

        assertBigDecimal(
                "100",
                trade.getPrice(),
                "trade.price"
        );
    }

    private void assertPositionQuantity(
            String expectedQuantity
    ) {

        PositionEntity position =
                waitForPosition();

        assertNotNull(
                position
        );

        assertEquals(
                SYMBOL,
                position.getSymbol()
        );

        assertEquals(
                STRATEGY_ID,
                position.getStrategyId()
        );

        assertBigDecimal(
                expectedQuantity,
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

    private PositionEntity waitForPosition() {

        Instant deadline =
                Instant.now()
                        .plus(
                                Duration.ofSeconds(15)
                        );

        PositionEntity current = null;

        while (
                Instant.now().isBefore(
                        deadline
                )
        ) {

            current =
                    positionRepository
                            .findBySymbolAndStrategyId(
                                    SYMBOL,
                                    STRATEGY_ID
                            )
                            .orElse(null);

            if (current != null) {
                return current;
            }

            sleep(100);
        }

        fail(
                "Position не создана за 15 секунд. " +
                        "symbol=" + SYMBOL +
                        ", strategyId=" + STRATEGY_ID
        );

        return null;
    }

    private void drainOutbox() {

        for (int i = 0; i < 30; i++) {

            outboxProcessor.processOutbox();

            boolean hasPending =
                    outboxEventRepository
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
                "Outbox chain не удалось полностью обработать " +
                        "за 30 проходов"
        );
    }

    private void seedRiskState() {

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

        riskState.setHalted(
                false
        );

        riskState.setUpdatedAt(
                Instant.now()
        );

        riskStateRepository.saveAndFlush(
                riskState
        );
    }

    private void configureSystemState() {

        Mockito.when(
                systemStateManager.isTradingEnabled()
        ).thenReturn(true);

        Mockito.when(
                systemStateManager.isReady()
        ).thenReturn(true);
    }

    private String normalize(
            BigDecimal value
    ) {

        return value
                .stripTrailingZeros()
                .toPlainString();
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

            Thread.sleep(
                    millis
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            fail(
                    "Test thread был прерван"
            );
        }
    }
}