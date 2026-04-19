package com.tradingbot.domain.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RiskEngineTest {
    private RiskStateReducer reducer;
    private RiskStateStore riskStateStore;
    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        reducer = new RiskStateReducer();
        riskStateStore = new RiskStateStore();

        riskEngine = new RiskEngine(
            reducer,
            riskStateStore
        );
    }

    @Test
    void shouldBlockEventsWhenHalted() {
        RiskState state = RiskState.empty().toBuilder().halted(true).build();
        riskStateStore.updateInternal(state);

        // Try to process trade
        RiskEvent tradeEvent = new RiskEvent.TradeExecuted(
            UUID.randomUUID().toString(),
            "BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.valueOf(50000),
            BigDecimal.ZERO,
            Instant.now()
        );

        RiskState result = riskEngine.process(state, tradeEvent);

        // Verify state didn't change
        assertTrue(result.isHalted());
        assertEquals(state.getVersion(), result.getVersion());
    }

    @Test
    void shouldIncrementVersionAndPersist() {
        RiskState state = riskEngine.getState();
        RiskEvent event = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.process(state, event);
        
        assertTrue(riskEngine.getState().getVersion() > state.getVersion());
    }

    @Test
    void shouldBeDeterministicOnReplay() {
        // 1. Generate events
        Instant now = Instant.now();
        RiskEvent e1 = new RiskEvent.TradeExecuted(UUID.randomUUID().toString(), "BTC", BigDecimal.ONE, new BigDecimal("50000"), BigDecimal.ZERO, now);
        RiskEvent e2 = new RiskEvent.PriceUpdated(UUID.randomUUID().toString(), "BTC", new BigDecimal("51000"), now.plusSeconds(1));
        
        // 2. Process through engine
        RiskState s1 = riskEngine.process(riskEngine.getState(), e1);
        RiskState s2 = riskEngine.process(s1, e2);
        RiskState stateAfterPublish = riskEngine.getState();

        // 3. Manual replay through reducer starting from empty
        RiskState stateAfterReplay = RiskState.empty();
        stateAfterReplay = reducer.reduce(stateAfterReplay, e1);
        stateAfterReplay = reducer.reduce(stateAfterReplay, e2);

        // 4. Compare
        assertEquals(stateAfterReplay.getVersion(), stateAfterPublish.getVersion());
        assertEquals(stateAfterReplay.getDailyPnl(), stateAfterPublish.getDailyPnl());
        assertEquals(stateAfterReplay.getProcessedEventIds(), stateAfterPublish.getProcessedEventIds());
    }
}
