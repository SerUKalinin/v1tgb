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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskServiceTest {

    @Mock
    private RiskRepository riskRepository;

    @Mock
    private RiskStateReducer reducer;

    @Mock
    private RiskStateStore riskStateStore;

    @Mock
    private com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository riskReservationLogRepository;

    @Mock
    private com.tradingbot.domain.exchange.ExchangeFeasibilityPort feasibilityPort;

    @Mock
    private com.tradingbot.domain.exchange.OrderNormalizationService normalizationService;

    @InjectMocks
    private RiskService riskService;    private RiskState createValidRiskState() {
        return RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .halted(false)
                .version(0L)
                .build();
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

        riskService.publish(tradeEvent);

        verify(reducer, never()).reduce(any(), any());
        verify(riskRepository, never()).markEventProcessed(any(), any(), any());
    }

    @Test
    void shouldReserveCapital() {
        // Given
        RiskState initialState = createValidRiskState();
        RiskState newState = initialState.toBuilder()
                .version(1L)
                .build();

        when(riskRepository.get()).thenReturn(initialState);
        when(reducer.reduce(any(), any())).thenReturn(newState);

        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000");

        // When
        RiskDecision decision = riskService.reserve(orderId, amount);

        // Then
        assertThat(decision.isApproved()).isTrue();
        
        // Проверяем, что событие было помечено как обработанное
        verify(riskRepository).markEventProcessed(any(), eq(newState), any());
        
        // Проверяем, что лог резервирования был сохранен с правильными данными
        verify(riskReservationLogRepository).save(argThat(logEntity -> 
            logEntity.getOrderId().equals(orderId) && 
            logEntity.getAmount().compareTo(amount) == 0 &&
            "RESERVE".equals(logEntity.getEventType())
        ));
    }
    @Test
    void shouldThrowExceptionWhenRepositoryFails() {
        when(riskRepository.get()).thenThrow(new RuntimeException("DB Error"));

        RiskEvent event = new RiskEvent.PriceUpdated(UUID.randomUUID().toString(), "BTC", BigDecimal.ONE, Instant.now());

        assertThrows(RuntimeException.class, () -> riskService.publish(event));
    }
}
