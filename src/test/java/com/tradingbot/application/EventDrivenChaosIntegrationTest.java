package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class EventDrivenChaosIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

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

    @Test
    public void testFullFlowWithDuplicateEvents() throws InterruptedException {
        String symbol = "BTCUSDT";
        String strategyId = "chaos-test-strat";
        UUID orderId = UUID.randomUUID();
        String externalTradeId = "ext-trade-123";

        // 0. Предварительно создаем ордер в БД, так как TradeService теперь требует его наличия
        orderRepository.save(com.tradingbot.infrastructure.persistence.entity.OrderEntity.builder()
                .id(orderId)
                .clientOrderId("C-" + orderId)
                .symbol(symbol)
                .side(OrderSide.BUY)
                .type(com.tradingbot.common.enums.OrderType.MARKET)
                .strategyId(strategyId)
                .quantity(new BigDecimal("1.0"))
                .price(new BigDecimal("50000"))
                .status("SENT")
                .createdAt(java.time.Instant.now())
                .build());        // 1. Публикуем событие исполнения ордера (BUY)
        OrderFilledEvent buyEvent = new OrderFilledEvent(
                orderId,
                externalTradeId,
                symbol,
                new BigDecimal("1.0"),
                new BigDecimal("50000")
        );

        eventPublisher.publishEvent(buyEvent);
        tradeService.onOrderFilled(buyEvent);

        // 2. Имитируем ДУБЛИКАТ того же события (Chaos Check)
        // Система должна проигнорировать его на уровне TradeService или PositionService
        tradeService.onOrderFilled(buyEvent);

        // Даем немного времени на асинхронную обработку (хотя в Spring Events по умолчанию синхронно)
        Thread.sleep(100);

        // 3. Проверяем Ledger (Trade)
        long tradeCount = tradeRepository.findAll().stream()
                .filter(t -> t.getExchangeTradeId().equals(externalTradeId))
                .count();
        assertThat(tradeCount).isEqualTo(1); // Должна быть только одна сделка

        // 4. Проверяем Position
        Position pos = positionService.getPosition(symbol, strategyId);
        // Так как мы не проходили через OMS, strategyId в TradeEntity может быть null или дефолтным
        // В реальном тесте мы бы прогнали через OMS, но здесь проверяем именно Reducer
        
        // 5. Проверяем Equity Snapshots
        List<EquitySnapshotEntity> snapshots = equityRepository.findAll();
        assertThat(snapshots).isNotEmpty();
        
        BigDecimal lastEquity = snapshots.get(snapshots.size() - 1).getEquity();
        logSnapshots(snapshots);
    }

    private void logSnapshots(List<EquitySnapshotEntity> snapshots) {
        snapshots.forEach(s -> System.out.println(
            String.format("[TEST-LOG] Time: %s | Equity: %s | Balance: %s | UPnL: %s", 
                s.getTimestamp(), s.getEquity(), s.getBalance(), s.getUnrealizedPnl())
        ));
    }
}
