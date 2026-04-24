package com.tradingbot.domain.risk;

import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ConcurrentRiskStressTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            riskStateRepository.deleteAll();
            riskStateRepository.flush();
            return null;
        });
        
        riskEngine.syncBalance(new BigDecimal("1000.00"));
        
        transactionTemplate.execute(status -> {
            RiskStateEntity entity = riskStateRepository.findById("risk_core").orElseThrow();
            entity.setTotalEquity(new BigDecimal("1000.00"));
            riskStateRepository.saveAndFlush(entity);
            return null;
        });
    }


    @Test
    void shouldPreventDoubleSpendUnderHighConcurrency() throws Exception {
        int threadCount = 10;
        BigDecimal orderAmount = new BigDecimal("600.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger approvedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                transactionTemplate.execute(status -> {
                    RiskDecision decision = riskEngine.reserve(UUID.randomUUID(), orderAmount);
                    if (decision.isApproved()) {
                        approvedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                    return null;
                });
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        assertThat(approvedCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(9);

        BigDecimal finalBalance = riskEngine.getState().availableBalance();
        assertThat(finalBalance).isEqualByComparingTo(new BigDecimal("400.00"));
    }
}
