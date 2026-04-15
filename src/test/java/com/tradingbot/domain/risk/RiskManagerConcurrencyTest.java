package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskManagerConcurrencyTest {

    @Test
    void shouldHandleConcurrentFiltersSafely() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        RiskStateStore store = new RiskStateStore();
        // Инициализируем состояние через RiskEngine, как того требует новый контракт
        RiskEngine engine = new RiskEngine(null, null, null, null, store);
        engine.initialize(RiskState.empty().toBuilder()
                .totalEquity(new BigDecimal("100000"))
                .build());
        
        // Правило, которое всегда одобряет, но вносит небольшую задержку для имитации нагрузки
        RiskRule slowRule = (req, state) -> {
            try { Thread.sleep(10); } catch (InterruptedException e) {}
            return RiskDecision.approve();
        };

        ExchangeFilterService filterService = new ExchangeFilterService(List.of(slowRule), store);
        
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<RiskDecision>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                OrderRequest request = OrderRequest.builder()
                        .symbol("BTCUSDT")
                        .quantity(BigDecimal.ONE)
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .build();
                return filterService.filter(request);
            }));
        }

        latch.countDown();
        
        int rejectedByLock = 0;
        for (Future<RiskDecision> future : futures) {
            try {
                RiskDecision decision = future.get();
                if (!decision.isApproved() && decision.getReason().contains("Concurrent")) {
                    rejectedByLock++;
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        // Хотя бы один должен быть отклонен из-за tryLock(), так как все 10 потоков бьют в один символ одновременно
        assertTrue(rejectedByLock > 0, "At least one request should be rejected by concurrent lock");
    }
}
