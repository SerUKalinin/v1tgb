package com.tradingbot.domain.risk;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentRiskStressTest extends BaseIntegrationTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status ->
                riskStateRepository.deleteAll());

        riskEngine.syncBalance(new BigDecimal("1000.00"));
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
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        UUID signalId = UUID.randomUUID();
                        ExecutionContext context = new ExecutionContext(
                                IdentityContext.of(signalId),
                                ExecutionAttemptContext.firstAttempt(signalId),
                                BusinessContext.empty()
                        );
                        RiskDecision decision = riskEngine.reserve(context, orderAmount);
                        if (decision.isApproved()) {
                            approvedCount.incrementAndGet();
                        } else {
                            rejectedCount.incrementAndGet();
                        }
                    });
                } catch (Exception e) {
                    rejectedCount.incrementAndGet();
                }
            }, executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        assertThat(approvedCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(9);

        BigDecimal finalBalance = riskEngine.getState().getBalance();
        assertThat(finalBalance).isEqualByComparingTo(new BigDecimal("400.00"));
    }
}
