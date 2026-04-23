package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.position.PositionReducer;
import com.tradingbot.domain.position.PositionState;
import com.tradingbot.domain.position.PositionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StateInvariantTest {

    @Test
    void shouldMaintainEquityAndPositionConsistency() {

        // 1. Risk state
        RiskState riskState = RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .dailyPnl(BigDecimal.ZERO)
                .maxEquity(new BigDecimal("10000"))
                .symbolExposures(Map.of("BTCUSDT", BigDecimal.ZERO))
                .processedEventIds(Collections.emptySet())
                .build();

        // 2. PositionState (АКТУАЛЬНЫЙ record-конструктор)
        PositionState positionState = new PositionState(
                "BTCUSDT",                    // symbol
                "strat-1",                   // strategyId
                BigDecimal.ZERO,             // netQuantity
                BigDecimal.ZERO,             // averagePrice
                UUID.randomUUID(),           // lastTradeId
                BigDecimal.ZERO,             // realizedPnL
                null,                        // stopLoss
                null,                        // takeProfit
                PositionStatus.NEW,          // status
                null,                        // closeRequestId
                Instant.now()                // updatedAt
        );

        BigDecimal qty = new BigDecimal("0.1");
        BigDecimal price = new BigDecimal("60000");

        // 3. TradeCreatedEvent (НОВАЯ СИГНАТУРА)
        TradeCreatedEvent event = new TradeCreatedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "BTCUSDT",
                "strat-1",
                qty,
                price,
                OrderSide.BUY,
                BigDecimal.ZERO, // realizedPnL
                BigDecimal.ZERO  // fees
        );
        // 4. Reduce
        PositionReducer reducer = new PositionReducer();
        PositionState nextState = reducer.reduce(positionState, event);

        // 5. Проверки (record -> геттеры через методы)
        assertEquals(0, qty.compareTo(nextState.netQuantity()));
        assertEquals(0, price.compareTo(nextState.averagePrice()));

        // 6. Unrealized PnL
        BigDecimal newPrice = new BigDecimal("50000");
        BigDecimal unrealized = newPrice.subtract(price).multiply(qty);

        BigDecimal equity = riskState.getTotalEquity().add(unrealized);

        assertEquals(0, new BigDecimal("9000").compareTo(equity));

        // 7. Risk halt
        RiskStateReducer riskReducer = new RiskStateReducer();

        RiskEvent.TradeExecuted hugeLoss = new RiskEvent.TradeExecuted(
                "evt-1",
                "BTCUSDT",
                qty,
                price,
                new BigDecimal("-2000"),
                Instant.now()
        );

        RiskState halted = riskReducer.reduce(riskState, hugeLoss);

        assertTrue(halted.isHalted(), "State must be halted if loss > 5%");
    }
}