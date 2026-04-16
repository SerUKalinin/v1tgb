package com.tradingbot.application;

import com.tradingbot.application.pipeline.TradingPipeline;
import com.tradingbot.application.service.PositionService;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.strategy.TradingStrategy;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.application.service.OrderManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void shouldExecuteTradeAndSavePositionWhenBuySignalReceived() throws InterruptedException {        System.out.println("=== INTEGRATION TEST STARTED ===");
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

        // 2. Risk
        when(riskManager.check(any())).thenReturn(
                RiskDecision.approve(amount)
        );
        when(riskManager.evaluate(any())).thenReturn(
                RiskDecision.approve(amount)
        );

        // 3. Execution Engine Mock
        when(executionEngine.execute(any())).thenAnswer(invocation -> {
            OrderRequest req = invocation.getArgument(0);
            System.out.println("=== MOCK EXECUTION CALLED FOR " + req.getSymbol() + " ===");
            
            String orderId = req.getOrderId() != null ? req.getOrderId() : "test-order";
            String tradeId = "trade-1";

            ExecutionResult result = ExecutionResult.success(
                    orderId,
                    "exchange-order-1",
                    tradeId,
                    req.getSymbol(),
                    req.getSide(),
                    req.getAmount(),
                    req.getPrice(),
                    BigDecimal.ZERO,
                    "USDT",
                    req.getClientOrderId()
            );

            // Публикуем событие исполнения, чтобы сработал Ledger и Position Reducer
            eventPublisher.publishEvent(new OrderFilledEvent(
                    orderId,
                    tradeId,
                    req.getSymbol(),
                    req.getAmount(),
                    req.getPrice()
            ));

            return result;
        });
        // ACT
        System.out.println("=== CALLING PIPELINE.PROCESS ===");
        tradingPipeline.process(window);
        
        // Даем немного времени на обработку событий в памяти
        Thread.sleep(200);
        
        System.out.println("=== PIPELINE.PROCESS FINISHED ===");
        // ASSERT
        Position pos = positionService.getPosition(symbol, strategyId);
        System.out.println("=== FINAL POSITION CHECK: " + pos + " ===");
        
        assertTrue(positionService.hasOpenPosition(symbol, strategyId), 
                "Позиция должна быть открыта. Текущее состояние: " + pos);
        
        assertEquals(symbol, pos.getSymbol());
        assertEquals(0, price.compareTo(pos.getAvgEntryPrice()), "Цена входа должна совпадать");
        System.out.println("=== INTEGRATION TEST SUCCESS ===");
    }
}
