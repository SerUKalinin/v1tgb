package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.market.ExchangeMetadataProvider;
import com.tradingbot.domain.model.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialSafetyTest {

    private DefaultRiskManager riskManager;
    private RiskStateStore stateStore;
    private RiskStateReducer reducer;
    private ExchangeMetadataProvider metadataProvider;

    @BeforeEach
    void setUp() {
        stateStore = new RiskStateStore();
        reducer = new RiskStateReducer();
        metadataProvider = mock(ExchangeMetadataProvider.class);
        
        // Mock default exchange constraints
        when(metadataProvider.getLotSize(anyString())).thenReturn(new BigDecimal("0.01"));
        when(metadataProvider.getQuantityPrecision(anyString())).thenReturn(2);

        riskManager = new DefaultRiskManager(metadataProvider);
        
        RiskState initialState = RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .dailyPnl(BigDecimal.ZERO)
                .maxEquity(new BigDecimal("10000"))
                .halted(false)
                .processedEventIds(Collections.emptySet())
                .symbolExposures(Collections.emptyMap())
                .version(0)
                .build();
        stateStore.updateInternal(initialState);
    }

    @Test
    void testDailyLossLimitAutoHalt() {
        // 1. Симулируем убыток > 5% (501 из 10000)
        RiskEvent.TradeExecuted lossEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                new BigDecimal("1"),
                new BigDecimal("10000"),
                new BigDecimal("-501"), // Realized PnL
                Instant.now()
        );

        RiskState newState = reducer.reduce(stateStore.getState(), lossEvent);
        stateStore.updateInternal(newState);

        assertTrue(stateStore.getState().isHalted(), "System should be halted after 5% daily loss");

        // 2. Проверяем, что RiskManager отклоняет новые сигналы
        Signal signal = Signal.builder()
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .price(new BigDecimal("10000"))
                .strategyId("test-strat")
                .clientOrderId("c1")
                .generatedAt(Instant.now())
                .build();

        Optional<ApprovedOrder> approvedOrder = riskManager.approveSignal(signal, stateStore.getState());
        assertTrue(approvedOrder.isEmpty(), "RiskManager should reject signals when halted");
    }

    @Test
    void testCentralizedSizingIntegrity() {
        // Equity = 10000, Risk = 1%, Price = 100 -> Qty should be 1.0
        Signal signal = Signal.builder()
                .symbol("ETHUSDT")
                .side(OrderSide.BUY)
                .price(new BigDecimal("100"))
                .strategyId("test-strat")
                .clientOrderId("c2")
                .generatedAt(Instant.now())
                .build();

        Optional<ApprovedOrder> approvedOrder = riskManager.approveSignal(signal, stateStore.getState());
        
        assertTrue(approvedOrder.isPresent());
        assertEquals(0, new BigDecimal("1.00").compareTo(approvedOrder.get().getQuantity()), 
                "Quantity should be 1% of equity divided by price");
    }
}
