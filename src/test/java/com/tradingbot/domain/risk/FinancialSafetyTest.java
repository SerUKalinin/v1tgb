package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.DefaultRiskManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.risk.RiskStateStore;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialSafetyTest {

    private DefaultRiskManager riskManager;
    private RiskService riskService;
    private RiskStateStore stateStore;
    private RiskStateReducer reducer;

    @BeforeEach
    void setUp() {
        stateStore = new RiskStateStore();
        reducer = new RiskStateReducer();
        riskService = mock(RiskService.class);
        RiskEngine riskEngine = mock(RiskEngine.class);
        ExecutionLogger executionLogger = mock(ExecutionLogger.class);

        riskManager = new DefaultRiskManager(riskService, riskEngine, executionLogger);

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

        stateStore.updateCache(initialState);
        when(riskService.getState()).thenReturn(initialState);
    }

    @Test
    @DisplayName("Система должна останавливать торговлю при превышении лимита дневного убытка")
    void testDailyLossLimitAutoHalt() {
        RiskEvent.TradeExecuted lossEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                new BigDecimal("1"),
                new BigDecimal("10000"),
                new BigDecimal("-501"),
                Instant.now()
        );

        RiskState safeInitialState = stateStore.getState().toBuilder()
                .balance(new BigDecimal("9000"))
                .totalEquity(new BigDecimal("10000"))
                .build();
        stateStore.updateCache(safeInitialState);

        RiskState newState = reducer.reduce(stateStore.getState(), lossEvent);
        stateStore.updateCache(newState);

        assertTrue(stateStore.getState().isHalted(),
                "System should be halted after 5% daily loss");

        when(riskService.getState()).thenReturn(stateStore.getState());

        Order order = Order.createPendingExecution(
                UUID.randomUUID(),
                "test-" + UUID.randomUUID(),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                BigDecimal.ONE,
                null,
                "STRAT-1",
                UUID.randomUUID()
        );

        RiskDecision decision = riskManager.check(order);

        assertFalse(decision.isApproved(),
                "RiskManager should reject signals when halted");
        assertEquals(RiskDecision.Reason.HALTED, decision.getReason());
    }

    @Test
    @DisplayName("Расчет объема позиции должен соответствовать риск-политике (1% от капитала)")
    void testCentralizedSizingIntegrity() {
        Signal signal = new Signal(
                UUID.randomUUID(),
                "ETHUSDT",
                "test-strat",
                SignalType.BUY,
                new BigDecimal("100"),
                BigDecimal.ZERO
        );

        when(riskService.getState()).thenReturn(stateStore.getState());

        RiskDecision decision = riskManager.evaluate(signal);

        assertTrue(decision.isApproved());
        assertEquals(0, new BigDecimal("1.00").compareTo(decision.getAmount()),
                "Quantity should be 1% of equity divided by price");
    }
}
