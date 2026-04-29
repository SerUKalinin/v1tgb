package com.tradingbot.domain.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskEngineTest {

    @Mock
    private RiskRepository riskRepository;

    @Mock
    private RiskStateReducer reducer;

    @Mock
    private RiskStateStore riskStateStore;

    @InjectMocks
    private RiskEngine riskEngine;

    @BeforeEach
    void setUp() {
        // Базовая настройка: репозиторий всегда возвращает валидное состояние
        lenient().when(riskRepository.get()).thenReturn(createValidRiskState());
    }

    @Test
    void shouldBlockEventsWhenHalted() {
        RiskState haltedState = createValidRiskState().toBuilder().halted(true).build();
        when(riskRepository.get()).thenReturn(haltedState);

        RiskEvent tradeEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(tradeEvent);

        // Должен проверить halted и выйти до вызова reducer и сохранения
        verify(reducer, never()).reduce(any(), any());
        verify(riskRepository, never()).markEventProcessed(any(), any(), any());
    }

    @Test
    void shouldReserveCapital() {
        RiskState initialState = createValidRiskState();
        RiskState newState = initialState.toBuilder()
                .reserved(new BigDecimal("1000"))
                .build();

        when(riskRepository.get()).thenReturn(initialState);
        when(riskRepository.isEventProcessed(any())).thenReturn(false);
        when(reducer.reduce(any(), any())).thenReturn(newState);

        UUID orderId = UUID.randomUUID();
        riskEngine.reserve(orderId, new BigDecimal("1000"));

        verify(riskRepository, times(1)).markEventProcessed(any(), eq(newState), any());
    }

    @Test
    void shouldIncrementVersionAndPersist() {
        RiskState initialState = createValidRiskState();
        RiskState newState = initialState.toBuilder().version(1L).build();

        when(riskRepository.get()).thenReturn(initialState);
        when(riskRepository.isEventProcessed(any())).thenReturn(false);
        when(reducer.reduce(any(), any())).thenReturn(newState);

        RiskEvent event = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(event);

        verify(riskRepository, times(1)).markEventProcessed(any(), eq(newState), eq(event));
    }

    @Test
    void shouldThrowExceptionWhenRepositoryFails() {
        when(riskRepository.get()).thenThrow(new RuntimeException("DB Error"));

        RiskEvent event = new RiskEvent.PriceUpdated(UUID.randomUUID().toString(), "BTC", BigDecimal.ONE, Instant.now());

        assertThrows(RuntimeException.class, () -> riskEngine.publish(event));
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
