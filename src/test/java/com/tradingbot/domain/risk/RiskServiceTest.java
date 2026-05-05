package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.RiskStateStore;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskServiceTest {

    @Mock
    private RiskStatePort riskStatePort;

    @Mock
    private RiskStateReducer reducer;

    @Mock
    private RiskReservationLogPort riskReservationLogPort;

    @Mock
    private RiskStateStore riskStateStore;

    @InjectMocks
    private RiskService riskService;

    private RiskState createValidRiskState() {
        return RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .halted(false)
                .version(0L)
                .build();
    }

    @Test
    void shouldBlockEventsWhenHalted() {
        RiskState haltedState = createValidRiskState().toBuilder()
                .halted(true)
                .build();

        when(riskStatePort.get()).thenReturn(haltedState);

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
        verify(riskStatePort, never()).save(any());
        verify(riskStatePort, never()).markEventProcessed(any(), any(), any());
        verify(riskReservationLogPort, never()).append(any());
        verify(riskStateStore, never()).updateCache(any());
    }

    @Test
    void shouldReserveCapital() {
        RiskState initialState = createValidRiskState();

        RiskState newState = initialState.toBuilder()
                .version(1L)
                .build();

        when(riskStatePort.get()).thenReturn(initialState);
        when(reducer.reduce(any(), any())).thenReturn(newState);

        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000");

        RiskDecision decision = riskService.reserve(orderId, amount);

        assertThat(decision.isApproved()).isTrue();

        verify(riskStatePort).save(any());
        verify(riskStatePort).markEventProcessed(any(), eq(newState), any());

        verify(riskReservationLogPort).append(argThat(log ->
                log.orderId().equals(orderId) &&
                        log.amount().compareTo(amount) == 0 &&
                        log.eventType() == RiskReservationEventType.RESERVE
        ));

        verify(riskStateStore).updateCache(eq(newState));
    }

    @Test
    void shouldThrowExceptionWhenPortFails() {
        when(riskStatePort.get()).thenThrow(new RuntimeException("DB Error"));

        RiskEvent event = new RiskEvent.PriceUpdated(
                UUID.randomUUID().toString(),
                "BTC",
                BigDecimal.ONE,
                Instant.now()
        );

        assertThrows(RuntimeException.class,
                () -> riskService.publish(event));

        verifyNoInteractions(reducer);
        verifyNoInteractions(riskReservationLogPort);
        verifyNoInteractions(riskStateStore);
    }
}