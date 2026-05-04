package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.OrderNormalizationService;
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

        riskManager = new DefaultRiskManager(
                Collections.emptyList(),
                riskService,
                mock(ExchangeFeasibilityPort.class),
                mock(OrderNormalizationService.class)
        );

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
        // 1. Симулируем убыток > 5% (501 из 10000)
        // Чтобы не нарушить инвариант balance + reserved <= equity, 
        // при уменьшении equity (из-за убытка) мы должны также уменьшить balance.
        RiskEvent.TradeExecuted lossEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                new BigDecimal("1"),
                new BigDecimal("10000"),
                new BigDecimal("-501"), // Realized PnL
                Instant.now()
        );

        // В handleTradeExecuted: equity = equity + pnl, но balance не меняется.
        // Чтобы инвариант (balance <= equity) сохранился, нужно либо чтобы balance изначально был меньше,
        // либо чтобы событие также корректировало баланс (но TradeExecuted в текущем Reducer этого не делает).
        // Поэтому для теста инициализируем состояние, где balance < equity на величину возможного убытка.
        
        RiskState safeInitialState = stateStore.getState().toBuilder()
                .balance(new BigDecimal("9000")) // Запас прочности для убытка
                .totalEquity(new BigDecimal("10000"))
                .build();
        stateStore.updateCache(safeInitialState);

        RiskState newState = reducer.reduce(stateStore.getState(), lossEvent);
        stateStore.updateCache(newState);

        assertTrue(stateStore.getState().isHalted(), "System should be halted after 5% daily loss");

        // 2. Настраиваем мок на новое (остановленное) состояние
        when(riskService.getState()).thenReturn(stateStore.getState());

        // 3. Проверяем, что RiskManager отклоняет проверку ордера
        Order order = Order.builder()
                .symbol("BTCUSDT")
                .originalQuantity(BigDecimal.ONE)
                .build();

        RiskDecision decision = riskManager.check(order);

        assertFalse(decision.isApproved(), "RiskManager should reject signals when halted");
        // В новой архитектуре reason — это Enum, а не String.
        assertEquals(RiskDecision.Reason.HALTED, decision.getReason());
    }    @Test
    @DisplayName("Расчет объема позиции должен соответствовать риск-политике (1% от капитала)")
    void testCentralizedSizingIntegrity() {
        // Equity = 10000, Risk = 1% (100 USDT), Price = 100 -> Qty should be 1.0
        com.tradingbot.domain.model.Signal signal = new com.tradingbot.domain.model.Signal(
                "ETHUSDT",
                "test-strat",
                com.tradingbot.common.enums.SignalType.BUY,
                new BigDecimal("100"),
                BigDecimal.ZERO
        );

        // DefaultRiskManager использует RiskService для получения актуального эквити
        when(riskService.getState()).thenReturn(stateStore.getState());

        BigDecimal quantity = riskManager.calculateQuantity(signal, stateStore.getState());

        assertNotNull(quantity);
        assertEquals(0, new BigDecimal("1.00").compareTo(quantity),
                "Quantity should be 1% of equity divided by price");
    }}
