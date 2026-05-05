package com.tradingbot.domain.risk;

import com.tradingbot.application.risk.DefaultRiskManager;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import java.util.ArrayList;
import static org.mockito.Mockito.mock;

class RiskManagerConcurrencyTest {

    @Test
    void concurrentRiskCheckShouldBeThreadSafe() throws InterruptedException {
        RiskService riskService = mock(RiskService.class);
        DefaultRiskManager riskManager = new DefaultRiskManager(riskService);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);

        SignalEvent event = new SignalEvent(
                "BTCUSDT",
                SignalType.BUY,
                new BigDecimal("50000"),
                BigDecimal.ONE,
                null,
                null,
                Instant.now(),
                "STRAT-1"
        );

        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    riskManager.approveSignal(event);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();
    }
}
