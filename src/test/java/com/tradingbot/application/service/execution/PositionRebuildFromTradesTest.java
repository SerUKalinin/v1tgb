package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PositionRebuildFromTradesTest {

    @Autowired
    private PositionRebuildService rebuildService;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private com.tradingbot.infrastructure.persistence.repository.OrderRepository orderRepository;

    @Test
    @Transactional
    @DisplayName("Position MUST be correctly rebuilt from trade history (PnL and AvgPrice check)")
    void shouldRebuildPositionFromTrades() {
        // Given
        String symbol = "BTCUSDT";
        String strategyId = "STRAT-1";

        // Создаем родительский ордер для трейдов (обязательно для TradeEntity)
        com.tradingbot.infrastructure.persistence.entity.OrderEntity order = new com.tradingbot.infrastructure.persistence.entity.OrderEntity();
        order.setId(UUID.randomUUID());
        order.setClientOrderId("ORD-" + UUID.randomUUID());
        order.setSymbol(symbol);
        order.setStrategyId(strategyId);
        order.setSide(com.tradingbot.common.enums.OrderSide.BUY);
        order.setType(com.tradingbot.common.enums.OrderType.MARKET);
        order.setStatus(com.tradingbot.common.enums.OrderStatus.FILLED);
        order.setQuantity(new BigDecimal("2.0"));
        orderRepository.saveAndFlush(order);

        // 1. BUY 1 BTC @ 50000
        createTrade(order, symbol, strategyId, OrderSide.BUY, "1.0", "50000");
        // 2. BUY 1 BTC @ 60000 (Avg Price should be 55000)
        createTrade(order, symbol, strategyId, OrderSide.BUY, "1.0", "60000");
        // 3. SELL 0.5 BTC @ 70000 (Realized PnL: (70000-55000)*0.5 = 7500)
        createTrade(order, symbol, strategyId, OrderSide.SELL, "0.5", "70000");

        // Очищаем позиции перед тестом
        positionRepository.deleteAll();
        positionRepository.flush();

        // When
        rebuildService.rebuildAllPositions();

        // Then
        PositionEntity position = positionRepository.findAll().stream()
                .filter(p -> p.getSymbol().equals(symbol) && p.getStrategyId().equals(strategyId))
                .findFirst()
                .orElseThrow();

        assertEquals(0, new BigDecimal("1.5").compareTo(position.getQuantity()), "Quantity mismatch");
        assertEquals(0, new BigDecimal("55000").compareTo(position.getEntryPrice()), "Avg Price mismatch");
        assertEquals(0, new BigDecimal("7500").compareTo(position.getRealizedPnl()), "PnL mismatch");
    }

    private void createTrade(com.tradingbot.infrastructure.persistence.entity.OrderEntity order, String symbol, String strategyId, OrderSide side, String qty, String price) {
        TradeEntity trade = TradeEntity.builder()
                .id(UUID.randomUUID())
                .order(order)
                .symbol(symbol)
                .strategyId(strategyId)
                .side(side)
                .quantity(new BigDecimal(qty))
                .price(new BigDecimal(price))
                .executedAt(Instant.now())
                .clientOrderId(order.getClientOrderId())
                .exchangeTradeId("EX-" + UUID.randomUUID())
                .build();
        tradeRepository.saveAndFlush(trade);
    }
    private void createTrade(String symbol, String strategyId, OrderSide side, String qty, String price) {
        tradeRepository.saveAndFlush(TradeEntity.builder()
                .id(UUID.randomUUID())
                .symbol(symbol)
                .strategyId(strategyId)
                .side(side)
                .quantity(new BigDecimal(qty))
                .price(new BigDecimal(price))
                .executedAt(Instant.now())
                .clientOrderId("CL-" + UUID.randomUUID())
                .exchangeTradeId("EX-" + UUID.randomUUID())
                .build());
    }}
