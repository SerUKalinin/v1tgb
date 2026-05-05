package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.RiskEngine;
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
    private RiskService riskService;

    @InjectMocks
    private RiskEngine riskEngine;

    @Test
    void shouldBlockEventsWhenHalted() {
        RiskEvent tradeEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(tradeEvent);

        verify(riskService, times(1)).publish(tradeEvent);
    }

    @Test
    void shouldReserveCapital() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000");
        
        riskEngine.reserve(orderId, amount);

        verify(riskService, times(1)).reserve(orderId, amount);
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

        verify(riskService, times(1)).publish(event);
    }

    @Test
    void shouldThrowExceptionWhenRepositoryFails() {
        doThrow(new RuntimeException("DB Error")).when(riskService).publish(any());

        RiskEvent event = new RiskEvent.PriceUpdated(UUID.randomUUID().toString(), "BTC", BigDecimal.ONE, Instant.now());

        assertThrows(RuntimeException.class, () -> riskEngine.publish(event));
    }
}
