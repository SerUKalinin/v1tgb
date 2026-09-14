package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskEngineTest {

    @Mock
    private RiskService riskService;

    @InjectMocks
    private RiskEngine riskEngine;

    @Test
    void shouldDelegatePublishToRiskService() {
        RiskEvent.TradeExecuted tradeEvent = new RiskEvent.TradeExecuted(
                UUID.randomUUID().toString(),
                "BTCUSDT",
                BigDecimal.ONE,
                BigDecimal.valueOf(50000),
                BigDecimal.ZERO,
                Instant.now()
        );

        riskEngine.publish(tradeEvent);

        verify(riskService).publish(tradeEvent);
    }

    @Test
    void shouldReserveCapitalViaExecutionContext() {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1000");
        UUID signalId = UUID.randomUUID();

        ExecutionContext context = new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.of(orderId.toString())
        );

        riskEngine.reserve(context, amount);

        verify(riskService).reserve(context, eq(amount));
    }

    @Test
    void shouldPropagateExceptionFromRiskService() {
        doThrow(new RuntimeException("DB Error"))
                .when(riskService).publish(any());

        RiskEvent.PriceUpdated event = new RiskEvent.PriceUpdated(
                UUID.randomUUID().toString(),
                "BTC",
                BigDecimal.ONE,
                Instant.now()
        );

        assertThrows(RuntimeException.class, () -> riskEngine.publish(event));
    }
}
