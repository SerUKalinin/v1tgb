package com.tradingbot.domain.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskEngineTest {

    @Mock
    private RiskEventRepository eventRepository;

    @Mock
    private RiskSnapshotRepository snapshotRepository;

    @Mock
    private RiskStateStore riskStateStore;

    @Mock
    private ObjectMapper objectMapper;

    @Spy
    private RiskStateReducer reducer = new RiskStateReducer();

    @InjectMocks
    private RiskEngine riskEngine;

    @Test
    void shouldBlockEventsWhenHalted() throws Exception {
        RiskState haltedState = RiskState.empty()
                .toBuilder()
                .halted(true)
                .version(1)
                .build();

        when(riskStateStore.getState()).thenReturn(haltedState);
        when(eventRepository.existsByEventId(any())).thenReturn(false);
        when(eventRepository.findMaxVersionByAggregateId(anyString()))
                .thenReturn(java.util.Optional.of(1L));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        RiskEvent haltEvent = new RiskEvent.TradingHalted(
                UUID.randomUUID().toString(),
                "Manual halt",
                Instant.now()
        );

        riskEngine.publish(haltEvent);

        verify(eventRepository, times(1)).saveAndFlush(any());

        RiskEvent tradeEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(tradeEvent);

        verifyNoMoreInteractions(eventRepository);
    }

    @Test
    void shouldIncrementVersionAndPersist() throws Exception {
        when(riskStateStore.getState()).thenReturn(RiskState.empty());
        when(eventRepository.existsByEventId(any())).thenReturn(false);
        when(eventRepository.findMaxVersionByAggregateId(anyString()))
                .thenReturn(java.util.Optional.of(0L));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        RiskEvent event = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(event);

        verify(eventRepository, times(1)).saveAndFlush(any());
        verify(riskStateStore, times(1)).updateInternal(any());
    }

    @Test
    void shouldBeDeterministicOnReplay() throws Exception {
        when(eventRepository.existsByEventId(any())).thenReturn(false);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(eventRepository.findMaxVersionByAggregateId(anyString()))
                .thenReturn(java.util.Optional.of(0L), java.util.Optional.of(1L));

        RiskEvent e1 = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTC",
                BigDecimal.ONE,
                new BigDecimal("50000"),
                BigDecimal.ZERO,
                Instant.now()
        );

        RiskEvent e2 = new RiskEvent.PriceUpdated(
                UUID.randomUUID().toString(),
                "BTC",
                new BigDecimal("51000"),
                Instant.now().plusSeconds(1)
        );

        when(riskStateStore.getState())
                .thenReturn(RiskState.empty())
                .thenReturn(RiskState.empty().toBuilder().version(1).build());

        riskEngine.publish(e1);
        riskEngine.publish(e2);

        verify(riskStateStore, atLeastOnce()).updateInternal(any());
    }
}