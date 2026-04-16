package com.tradingbot.domain.risk;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskStateReplayTest {

    @Test
    void shouldReplayEventsDeterministically() {
        RiskStateReducer reducer = new RiskStateReducer();
        RiskState initialState = RiskState.empty().toBuilder()
                .totalEquity(new BigDecimal("10000"))
                .build();

        List<RiskEvent> events = List.of(
                new RiskEvent.TradeExecuted("ev-1", "BTCUSDT", new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("100"), Instant.now()),
                new RiskEvent.TradeExecuted("ev-2", "ETHUSDT", new BigDecimal("1.0"), new BigDecimal("3000"), new BigDecimal("-50"), Instant.now()),
                new RiskEvent.PriceUpdated("ev-3", "BTCUSDT", new BigDecimal("51000"), Instant.now())
        );
        RiskState state = initialState;
        for (RiskEvent event : events) {
            state = reducer.reduce(state, event);
        }

        // 10000 + 100 - 50 = 10050
        assertEquals(new BigDecimal("10050"), state.getTotalEquity());
        assertEquals(new BigDecimal("50"), state.getDailyPnl());
        assertEquals(new BigDecimal("5000.0"), state.getSymbolExposures().get("BTCUSDT"));
        assertEquals(new BigDecimal("3000.0"), state.getSymbolExposures().get("ETHUSDT"));
    }
}
