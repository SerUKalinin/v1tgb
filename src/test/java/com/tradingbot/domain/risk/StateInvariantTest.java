package com.tradingbot.domain.risk;

import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.PositionReducer;
import com.tradingbot.common.enums.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateInvariantTest {

    @Test
    void shouldMaintainEquityAndPositionConsistency() {
        // 1. Initial State
        RiskState riskState = RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .dailyPnl(BigDecimal.ZERO)
                .maxEquity(new BigDecimal("10000"))
                .symbolExposures(Map.of("BTCUSDT", BigDecimal.ZERO))
                .processedEventIds(Collections.emptySet())
                .build();

        Position position = Position.builder()
                .symbol("BTCUSDT")
                .strategyId("strat-1")
                .netQuantity(BigDecimal.ZERO)
                .avgEntryPrice(BigDecimal.ZERO)
                .build();

        // 2. Simulate Trade: BUY 0.1 BTC @ 60000
        BigDecimal tradeQty = new BigDecimal("0.1");
        BigDecimal tradePrice = new BigDecimal("60000");
        
        TradeCreatedEvent event = new TradeCreatedEvent(
                1L, "order-1", "BTCUSDT", "strat-1",
                tradeQty, tradePrice, OrderSide.BUY
        );

        // 3. Apply Transitions
        Position nextPosition = PositionReducer.reduce(position, event);
        
        // Invariant: Position quantity must match trade quantity for first trade
        assertEquals(0, tradeQty.compareTo(nextPosition.getNetQuantity()));
        assertEquals(0, tradePrice.compareTo(nextPosition.getAvgEntryPrice()));

        // 4. Simulate Price Drop to 50000 (Unrealized Loss)
        BigDecimal newPrice = new BigDecimal("50000");
        BigDecimal unrealizedPnl = newPrice.subtract(tradePrice).multiply(tradeQty); // (50000-60000)*0.1 = -1000
        
        BigDecimal currentEquity = riskState.getTotalEquity().add(unrealizedPnl);
        
        // Invariant: Equity must reflect unrealized PnL
        assertEquals(0, new BigDecimal("9000").compareTo(currentEquity));
        
        // 5. Check Halt Invariant
        RiskStateReducer riskReducer = new RiskStateReducer();
        // Simulate a huge loss to trigger halt
        RiskEvent.TradeExecuted hugeLoss = new RiskEvent.TradeExecuted(
                "evt-1", "BTCUSDT", tradeQty, tradePrice, new BigDecimal("-2000"), Instant.now()
        );
        RiskState haltedState = riskReducer.reduce(riskState, hugeLoss);
        
        assertTrue(haltedState.isHalted(), "State must be halted if loss > 5%");
    }
}
