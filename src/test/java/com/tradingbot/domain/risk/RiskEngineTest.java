package com.tradingbot.domain.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private RiskStateRepository riskStateRepository;

    @Mock
    private RiskStateStore riskStateStore;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RiskStateReducer reducer;

    @InjectMocks
    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        // Default behavior for common mocks to avoid NPE and ensure deterministic behavior
        lenient().when(riskStateRepository.findByIdForUpdate(anyString()))
                .thenReturn(Optional.of(createValidRiskStateEntity()));
        
        lenient().when(riskStateStore.getState())
                .thenReturn(createValidRiskState());
    }

    @Test
    void shouldBlockEventsWhenHalted() throws Exception {
        RiskState haltedState = createValidRiskState().toBuilder()
                .halted(true)
                .version(1)
                .build();

        RiskStateEntity haltedEntity = createValidRiskStateEntity();
        haltedEntity.setHalted(true);

        when(riskStateRepository.findByIdForUpdate(anyString())).thenReturn(Optional.of(haltedEntity));
        when(eventRepository.existsByEventId(any())).thenReturn(false);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        RiskEvent haltEvent = new RiskEvent.TradingHalted(
                UUID.randomUUID().toString(),
                "Manual halt",
                Instant.now()
        );

        // Reducer should return the same halted state
        when(reducer.reduce(any(), eq(haltEvent))).thenReturn(haltedState);

        riskEngine.publish(haltEvent);

        verify(eventRepository, times(1)).save(any());

        RiskEvent tradeEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(tradeEvent);

        // Should not call reducer or save event for trade when halted
        verify(reducer, never()).reduce(any(), eq(tradeEvent));
    }

    @Test
    void shouldReserveCapital() throws Exception {
        RiskState initialState = createValidRiskState();
        RiskState newState = initialState.toBuilder()
                .reserved(new BigDecimal("1000"))
                .build();

        when(eventRepository.existsByEventId(any())).thenReturn(false);
        when(reducer.reduce(any(), any())).thenReturn(newState);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        UUID orderId = UUID.randomUUID();
        riskEngine.reserve(orderId, new BigDecimal("1000"));

        verify(riskStateRepository, times(1)).saveAndFlush(any());
        verify(eventRepository, times(1)).save(any());
    }

    @Test
    void shouldIncrementVersionAndPersist() throws Exception {
        RiskState initialState = createValidRiskState();
        RiskState newState = initialState.toBuilder().version(1).build();

        when(eventRepository.existsByEventId(any())).thenReturn(false);
        when(reducer.reduce(any(), any())).thenReturn(newState);
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

        verify(riskStateRepository, times(1)).saveAndFlush(any());
        verify(eventRepository, times(1)).save(any());
        // In unit test, syncCacheAfterCommit might call updateCache directly if no transaction active
        verify(riskStateStore, times(1)).updateCache(newState);
    }

    @Test
    void shouldThrowExceptionWhenStateNotFound() {
        when(riskStateRepository.findByIdForUpdate(anyString())).thenReturn(Optional.empty());

        RiskEvent event = new RiskEvent.PriceUpdated(UUID.randomUUID().toString(), "BTC", BigDecimal.ONE, Instant.now());
        
        assertThrows(RuntimeException.class, () -> riskEngine.publish(event));
    }

    private RiskStateEntity createValidRiskStateEntity() {
        RiskStateEntity entity = new RiskStateEntity();
        entity.setId("risk_core");
        entity.setTotalEquity(new BigDecimal("10000"));
        entity.setAvailableBalance(new BigDecimal("10000"));
        entity.setReservedMargin(BigDecimal.ZERO);
        entity.setHalted(false);
        entity.setVersion(0L);
        return entity;
    }

    private RiskState createValidRiskState() {
        return RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .reserved(BigDecimal.ZERO)
                .halted(false)
                .version(0L)
                .build();
    }
}