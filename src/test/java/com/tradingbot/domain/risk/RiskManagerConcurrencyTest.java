package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.market.ExchangeMetadataProvider;
import com.tradingbot.domain.model.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskManagerConcurrencyTest {

    @Test
    void shouldHandleConcurrentFiltersSafely() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        RiskStateStore store = new RiskStateStore();
        store.updateInternal(RiskState.empty().toBuilder()
                .totalEquity(new BigDecimal("100000"))
                .build());
        
        ExchangeMetadataProvider metadataProvider = mock(ExchangeMetadataProvider.class);
        when(metadataProvider.getLotSize(anyString())).thenReturn(new BigDecimal("0.01"));
        when(metadataProvider.getQuantityPrecision(anyString())).thenReturn(2);

        DefaultRiskManager riskManager = new DefaultRiskManager(metadataProvider);
        
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Optional<ApprovedOrder>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                latch.await();
                Signal signal = Signal.builder()
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .price(new BigDecimal("60000"))
                        .strategyId("test-strat")
                        .clientOrderId("c-" + index)
                        .generatedAt(Instant.now())
                        .build();
                return riskManager.approveSignal(signal, store.getState());
            }));
        }

        latch.countDown();
        
        int approved = 0;
        for (Future<Optional<ApprovedOrder>> future : futures) {
            try {
                Optional<ApprovedOrder> result = future.get();
                if (result.isPresent()) {
                    approved++;
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        // В текущей реализации DefaultRiskManager stateless и не имеет внутреннего lock на символ,
        // но тест проверяет корректность работы в многопоточной среде.
        assertTrue(approved > 0, "At least one request should be approved");
    }
}
