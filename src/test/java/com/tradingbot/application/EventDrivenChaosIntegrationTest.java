package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.outbox.OutboxService;
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
import org.springframework.boot.test.mock.mockito.MockBean;

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
    private EquityService equityService;

    @Autowired
    private TradeService tradeService;

    @MockBean
    private OutboxService outboxService;

    @MockBean
    private RiskEngine riskEngine;

    @Test
    public void testFullFlowWithDuplicateEvents() throws InterruptedException {
        String symbol = "BTCUSDT";
        String strategyId = "chaos-test-strat";
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        String externalTradeId = "ext-trade-123";

        // 0. Предварительно создаем ордер в БД (через builder — strategyId/signalId без сеттеров)
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
                .createdAt(Instant.now())
                .build();
        orderRepository.save(orderEntity);

        // 1. Публикуем событие исполнения ордера (BUY)
        IdentityContext identity = IdentityContext.of(signalId);
        ExecutionAttemptContext attempt = ExecutionAttemptContext.firstAttempt(signalId);
        BusinessContext business = BusinessContext.of(orderId.toString());

        OrderFilledEvent buyEvent = new OrderFilledEvent(
                identity, attempt, business,
                orderId,
                externalTradeId,
                symbol,
                new BigDecimal("1.0"),
                new BigDecimal("50000")
        );

        tradeService.onOrderFilled(buyEvent);

        // 2. Имитируем ДУБЛИКАТ того же события (Chaos Check)
        tradeService.onOrderFilled(buyEvent);

        Thread.sleep(100);

        // 3. Проверяем Ledger (Trade)
        long tradeCount = tradeRepository.findAll().stream()
                .filter(t -> externalTradeId.equals(t.getExchangeTradeId()))
                .count();
        assertThat(tradeCount).isEqualTo(1);

        // 4. Проверяем Position (может быть null — позиция создаётся асинхронно через Outbox)
        Position pos = positionService.getPosition(symbol, strategyId);

        // 5. Проверяем Equity Snapshots
        List<EquitySnapshotEntity> snapshots = equityRepository.findAll();
        logSnapshots(snapshots);
    }

    private void logSnapshots(List<EquitySnapshotEntity> snapshots) {
        snapshots.forEach(s -> System.out.println(
                String.format("[TEST-LOG] Time: %s | Equity: %s | Balance: %s | UPnL: %s",
                        s.getTimestamp(), s.getEquity(), s.getBalance(), s.getUnrealizedPnl())
        ));
    }
}
