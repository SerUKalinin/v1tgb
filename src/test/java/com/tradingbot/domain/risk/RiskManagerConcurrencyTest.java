package com.tradingbot.domain.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.enums.SignalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskManagerConcurrencyTest {

    @Test
    void shouldHandleConcurrentFiltersSafely() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        RiskStateStore store = new RiskStateStore();
        // Инициализируем состояние
        store.updateInternal(RiskState.empty().toBuilder()
                .totalEquity(new BigDecimal("100000"))
                .build());
        
        // В Stage 3 мы используем DefaultRiskManager вместо ExchangeFilterService
        DefaultRiskManager riskManager = new DefaultRiskManager(List.of(), store);
        
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Optional<ApprovedOrder>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                SignalEvent signal = SignalEvent.builder()
                        .symbol("BTCUSDT")
                        .type(SignalType.BUY)
                        .price(new BigDecimal("60000"))
                        .strategyId("test-strat")
                        .build();
                return riskManager.approveSignal(signal);
            }));
        }

        latch.countDown();
        
        int rejectedByLock = 0;
        for (Future<Optional<ApprovedOrder>> future : futures) {
            try {
                Optional<ApprovedOrder> result = future.get();
                if (result.isEmpty()) {
                    rejectedByLock++;
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        // Хотя бы один должен быть отклонен из-за tryLock(), так как все 10 потоков бьют в один символ одновременно
        assertTrue(rejectedByLock > 0, "At least one request should be rejected by concurrent lock. Rejected: " + rejectedByLock);
    }
}
