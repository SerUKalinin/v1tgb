package com.tradingbot.application;

import com.tradingbot.application.pipeline.TradingPipeline;
import com.tradingbot.application.service.PositionService;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TradingPipelineIntegrationTest {

    @Autowired
    private TradingPipeline tradingPipeline;

    @Autowired
    private PositionService positionService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockBean
    private TradingStrategy tradingStrategy;

    @MockBean
    private RiskManager riskManager;

    @MockBean
    private com.tradingbot.domain.execution.ExecutionEngine executionEngine;

    @Test
    void shouldExecuteTradeAndSavePositionWhenBuySignalReceived() {
        System.out.println("=== INTEGRATION TEST STARTED ===");
        String symbol = "BTCUSDT";
        String strategyId = "simple-strategy";
        BigDecimal price = new BigDecimal("60000");
        BigDecimal amount = new BigDecimal("0.1");
        Instant now = Instant.now();
        
        Candle candle = Candle.builder()
                .openTime(now)
                .open(price)
                .high(price)
                .low(price)
                .close(price)
                .volume(BigDecimal.TEN)
                .closeTime(now.plusSeconds(60))
                .build();
        
        CandleWindow window = new CandleWindow(symbol, List.of(candle));
        
        // 1. Strategy
        when(tradingStrategy.analyze(any())).thenReturn(
                new Signal(symbol, strategyId, com.tradingbot.common.enums.SignalType.BUY, price, amount)
        );

        // 2. Risk Mock (Stage 3: approveSignal returns ApprovedOrder)
        ApprovedOrder approvedOrder = ApprovedOrder.builder()
                .orderId(UUID.randomUUID().toString())
                .clientOrderId("c-test")
                .symbol(symbol)
                .side(com.tradingbot.common.enums.OrderSide.BUY)
                .type(com.tradingbot.common.enums.OrderType.MARKET)
                .quantity(amount)
                .price(price)
                .strategyId(strategyId)
                .approvedAt(Instant.now())
                .riskStateVersion(1L)
                .build();

        when(riskManager.approveSignal(any())).thenReturn(Optional.of(approvedOrder));
        when(riskManager.isApprovalFresh(any())).thenReturn(true);

        // 3. Execution Engine Mock
        when(executionEngine.execute(any())).thenAnswer(invocation -> {
            ApprovedOrder order = invocation.getArgument(0);
            System.out.println("=== MOCK EXECUTION CALLED FOR " + order.getSymbol() + " ===");
            
            String tradeId = "trade-1";

            ExecutionResult result = ExecutionResult.success(
                    order.getOrderId(),
                    "exchange-order-1",
                    tradeId,
                    order.getSymbol(),
                    order.getSide(),
                    order.getQuantity(),
                    order.getPrice(),
                    BigDecimal.ZERO,
                    "USDT",
                    order.getClientOrderId()
            );

            // Публикуем событие исполнения, чтобы сработал Ledger и Position Reducer
            // Используем актуальный конструктор TradeCreatedEvent
            eventPublisher.publishEvent(new com.tradingbot.domain.event.TradeCreatedEvent(
                    1L,
                    order.getOrderId(),
                    order.getSymbol(),
                    order.getStrategyId(),
                    order.getQuantity(),
                    order.getPrice(),
                    order.getSide()
            ));

            return result;
        });
        // ACT
        System.out.println("=== CALLING PIPELINE.PROCESS ===");
        tradingPipeline.process(window);
        System.out.println("=== PIPELINE.PROCESS FINISHED ===");

        // ASSERT
        Position pos = positionService.getPosition(symbol, strategyId);
        System.out.println("=== FINAL POSITION CHECK: " + pos + " ===");
        
        assertNotNull(pos, "Позиция должна существовать в кэше/БД");
        assertTrue(pos.isOpen(), "Позиция должна быть открыта. Текущее состояние: " + pos);
        
        assertEquals(symbol, pos.getSymbol());
        assertEquals(0, price.compareTo(pos.getAvgEntryPrice()), "Цена входа должна совпадать");
        assertEquals(0, amount.compareTo(pos.getNetQuantity()), "Объем позиции должен совпадать с объемом сделки");
        System.out.println("=== INTEGRATION TEST SUCCESS ===");
    }
}
