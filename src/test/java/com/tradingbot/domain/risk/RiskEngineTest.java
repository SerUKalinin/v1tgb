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
    private RiskEventRepository eventRepository;
    private RiskSnapshotRepository snapshotRepository;
    private RiskStateReducer reducer;
    private RiskStateStore riskStateStore;
    private RiskEngine riskEngine;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        eventRepository = mock(RiskEventRepository.class);
        snapshotRepository = mock(RiskSnapshotRepository.class);
        reducer = new RiskStateReducer();
        riskStateStore = new RiskStateStore();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        riskEngine = new RiskEngine(
            eventRepository,
            snapshotRepository,
            reducer,
            objectMapper,
            riskStateStore
        );
    }

    @Test
    void shouldBlockEventsWhenHalted() {
        // 1. Halt the engine
        RiskEvent haltEvent = new RiskEvent.TradingHalted(
            UUID.randomUUID().toString(),
            "Manual halt",
            Instant.now()
        );
        riskEngine.publish(haltEvent);

        assertTrue(riskEngine.getState().isHalted());

        // 2. Try to publish trade
        RiskEvent tradeEvent = new RiskEvent.TradeExecuted(
            UUID.randomUUID().toString(),
            "BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.valueOf(50000),
            BigDecimal.ZERO,
            Instant.now()
        );

        riskEngine.publish(tradeEvent);

        // Verify version didn't increase (still 1 from halt event)
        assertEquals(1, riskEngine.getState().getVersion());
        verify(eventRepository, times(1)).save(any()); // Only halt event saved
    }

    @Test
    void shouldIncrementVersionAndPersist() {
        RiskEvent event = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(event);
    }

    @Test
    void shouldBeDeterministicOnReplay() {
        // 1. Generate events
        Instant now = Instant.now();
        RiskEvent e1 = new RiskEvent.TradeExecuted(UUID.randomUUID().toString(), "BTC", BigDecimal.ONE, new BigDecimal("50000"), BigDecimal.ZERO, now);
        RiskEvent e2 = new RiskEvent.PriceUpdated(UUID.randomUUID().toString(), "BTC", new BigDecimal("51000"), now.plusSeconds(1));
        
        // 2. Process through engine
        riskEngine.publish(e1);
        riskEngine.publish(e2);
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
