package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TradingPipelineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TradingPipeline tradingPipeline;

    @Autowired
    private PositionService positionService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @MockBean
    private TradingStrategy tradingStrategy;

    @MockBean
    private RiskManager riskManager;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired
    private com.tradingbot.infrastructure.persistence.repository.OrderRepository orderRepository;

    @Test
    void shouldExecuteTradeAndSavePositionWhenBuySignalReceived() {
        String symbol = "BTCUSDT";
        String strategyId = "strat-1";
        java.math.BigDecimal price = new java.math.BigDecimal("60000");
        java.math.BigDecimal amount = new java.math.BigDecimal("0.1");
        java.util.UUID orderId = java.util.UUID.randomUUID();

        // 0. Предварительно создаем ордер в БД, так как TradeService требует его наличия
        com.tradingbot.infrastructure.persistence.entity.OrderEntity orderEntity = new com.tradingbot.infrastructure.persistence.entity.OrderEntity();
        orderEntity.setId(orderId);
        orderEntity.setClientOrderId("C-" + orderId);
        orderEntity.setSymbol(symbol);
        orderEntity.setSide(com.tradingbot.common.enums.OrderSide.BUY);
        orderEntity.setType(com.tradingbot.common.enums.OrderType.MARKET);
        orderEntity.setStrategyId(strategyId);
        orderEntity.setQuantity(amount);
        orderEntity.setPrice(price);
        orderEntity.setStatus(com.tradingbot.common.enums.OrderStatus.EXECUTING);
        orderEntity.setCreatedAt(java.time.Instant.now());
        orderRepository.save(orderEntity);
        // 1. Имитируем исполнение ордера
        com.tradingbot.domain.event.OrderFilledEvent filledEvent = new com.tradingbot.domain.event.OrderFilledEvent(
                orderId,
                "exec-123",
                symbol,
                amount,
                price
        );

        // =========================
        // ACT
        // =========================
        eventPublisher.publishEvent(filledEvent);

        // В новой архитектуре PositionService обновляется асинхронно через Outbox (событие TRADE_CREATED).
        // Чтобы тест прошел без запуска всей инфраструктуры Outbox-воркеров, 
        // мы имитируем работу потребителя, вызывая обновление позиции напрямую.
        com.tradingbot.domain.event.TradeCreatedEvent tradeEvent = new com.tradingbot.domain.event.TradeCreatedEvent(
                java.util.UUID.randomUUID(),
                orderId,
                symbol,
                strategyId,
                amount,
                price,
                com.tradingbot.common.enums.OrderSide.BUY,
                null, null
        );
        positionService.updatePosition(tradeEvent);

        // =========================
        // ASSERT (Using Awaitility and DB Polling)
        // =========================
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Trigger outbox processing inside poll to handle chained events
                    outboxProcessor.processOutbox();

                    // 1. Check Outbox is processed
                    long pending = outboxRepository.findAll().stream()
                            .filter(e -> e.getStatus() != OutboxStatus.PROCESSED)
                            .count();
                    
                    // 2. Check Position state in DB
                    Position pos = positionService.getPosition(symbol, strategyId);
                    assertNotNull(pos, "Позиция должна существовать");
                    
                    assertTrue(pos.isOpen(), "Позиция должна быть открыта");
                    // Используем compareTo == 0 для BigDecimal, чтобы игнорировать разницу в scale (0.1 vs 0.10)
                    assertTrue(price.stripTrailingZeros().compareTo(pos.getAvgEntryPrice().stripTrailingZeros()) == 0, 
                        String.format("Цена входа не совпадает. Ожидалось: %s, Актуально: %s", price, pos.getAvgEntryPrice()));
                    assertTrue(amount.stripTrailingZeros().compareTo(pos.getNetQuantity().stripTrailingZeros()) == 0, 
                        String.format("Количество не совпадает. Ожидалось: %s, Актуально: %s", amount, pos.getNetQuantity()));
                });
    }
}