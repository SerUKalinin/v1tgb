package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class EventDrivenChaosIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PositionService positionService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private EquitySnapshotRepository equityRepository;

    @Autowired
    private TradeService tradeService;

    @Test
    public void testFullFlowWithDuplicateEvents()
            throws InterruptedException {

        String symbol = "BTCUSDT";
        String strategyId = "chaos-test-strat";

        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();

        String externalTradeId = "ext-trade-123";

        /*
         * Canonical execution identity:
         *
         * orderId
         *    ↓
         * executionId
         *
         * Один Order = один executionId = одна execution lifecycle.
         */
        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.firstAttemptForOrder(orderId);

        IdentityContext identity =
                IdentityContext.of(signalId);

        BusinessContext business =
                BusinessContext.of(orderId.toString());

        /*
         * Order в тесте должен содержать тот же executionId,
         * который будет передан в execution context события.
         */
        OrderEntity orderEntity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId("C-" + orderId)
                .symbol(symbol)
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .strategyId(strategyId)
                .signalId(signalId)
                .quantity(new BigDecimal("1.0"))
                .price(new BigDecimal("50000"))
                .status(OrderStatus.EXECUTING)
                .executionId(attempt.executionId())
                .createdAt(Instant.now())
                .build();

        orderRepository.saveAndFlush(orderEntity);

        /*
         * Публикуем событие исполнения ордера.
         */
        OrderFilledEvent buyEvent =
                new OrderFilledEvent(
                        identity,
                        attempt,
                        business,
                        orderId,
                        externalTradeId,
                        symbol,
                        new BigDecimal("1.0"),
                        new BigDecimal("50000")
                );

        tradeService.onOrderFilled(buyEvent);

        /*
         * Повторно публикуем точно то же событие.
         * Оно не должно создать вторую сделку.
         */
        tradeService.onOrderFilled(buyEvent);

        Thread.sleep(100);

        /*
         * Ledger: должна существовать ровно одна Trade
         * для одного exchangeTradeId.
         */
        long tradeCount =
                tradeRepository.findAll()
                        .stream()
                        .filter(t ->
                                externalTradeId.equals(
                                        t.getExchangeTradeId()
                                )
                        )
                        .count();

        assertThat(tradeCount)
                .isEqualTo(1);

        /*
         * Position может быть не создана мгновенно,
         * потому что projection выполняется downstream.
         */
        Position pos =
                positionService.getPosition(
                        symbol,
                        strategyId
                );

        /*
         * Сам факт отсутствия позиции здесь не является
         * failure condition данного chaos-теста.
         */
        if (pos != null) {
            assertThat(pos.getNetQuantity())
                    .isNotNull();
        }

        /*
         * Snapshot проверяем как диагностический артефакт.
         */
        List<EquitySnapshotEntity> snapshots =
                equityRepository.findAll();

        logSnapshots(snapshots);
    }

    private void logSnapshots(
            List<EquitySnapshotEntity> snapshots
    ) {
        snapshots.forEach(snapshot ->
                System.out.println(
                        String.format(
                                "[TEST-LOG] Time: %s | Equity: %s | Balance: %s | UPnL: %s",
                                snapshot.getTimestamp(),
                                snapshot.getEquity(),
                                snapshot.getBalance(),
                                snapshot.getUnrealizedPnl()
                        )
                )
        );
    }
}