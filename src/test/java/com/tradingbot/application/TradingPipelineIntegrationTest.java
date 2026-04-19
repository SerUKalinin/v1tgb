package com.tradingbot.application;

import com.tradingbot.application.pipeline.TradingPipeline;
import com.tradingbot.application.service.PositionService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.position.PortfolioState;
import com.tradingbot.domain.position.PositionState;
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
import java.util.Map;
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

        // 1. Strategy mock
        when(tradingStrategy.decide(any(), any())).thenReturn(
                Optional.of(Signal.builder()
                        .symbol(symbol)
                        .strategyId(strategyId)
                        .side(OrderSide.BUY)
                        .price(price)
                        .quantity(amount)
                        .clientOrderId("c-test")
                        .generatedAt(now)
                        .build())
        );

        // 2. Risk mock
        ApprovedOrder approvedOrder = ApprovedOrder.builder()
                .orderId(UUID.randomUUID().toString())
                .clientOrderId("c-test")
                .symbol(symbol)
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(amount)
                .price(price)
                .strategyId(strategyId)
                .approvedAt(Instant.now())
                .riskStateVersion(1L)
                .build();

        when(riskManager.approveSignal(any(), any())).thenReturn(Optional.of(approvedOrder));
        when(riskManager.isApprovalFresh(any(), any())).thenReturn(true);

        // 3. Execution mock
        when(executionEngine.execute(any())).thenAnswer(invocation -> {
            ApprovedOrder order = invocation.getArgument(0);

            ExecutionResult result = ExecutionResult.success(
                    order.getOrderId(),
                    "exchange-order-1",
                    "trade-1",
                    order.getSymbol(),
                    order.getSide(),
                    order.getQuantity(),
                    order.getPrice(),
                    BigDecimal.ZERO,
                    "USDT",
                    order.getClientOrderId()
            );

            // 🔥 ВАЖНО: новый конструктор (fee + pnl)
            eventPublisher.publishEvent(new TradeCreatedEvent(
                    1L,
                    order.getOrderId(),
                    order.getSymbol(),
                    order.getStrategyId(),
                    order.getQuantity(),
                    order.getPrice(),
                    order.getSide(),
                    BigDecimal.ZERO, // fee
                    BigDecimal.ZERO  // realized pnl
            ));

            return result;
        });

        // ACT
        tradingPipeline.process(window);

        // ASSERT
        Position pos = positionService.getPosition(symbol, strategyId);

        assertNotNull(pos, "Позиция должна существовать");
        assertTrue(pos.isOpen(), "Позиция должна быть открыта");

        assertEquals(symbol, pos.getSymbol());
        assertEquals(0, price.compareTo(pos.getAvgEntryPrice()));
        assertEquals(0, amount.compareTo(pos.getNetQuantity()));
    }
}