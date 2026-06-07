package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionClaimConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private ExecutionClaimAdapter claimAdapter;

    @Autowired
    private ExecutionClaimRepository repository;

    @Test
    void testParallelClaimReturnsSameResult() throws Exception {
        UUID signalId = UUID.randomUUID();

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CompletableFuture<?>[] futures = new CompletableFuture[threadCount];

        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                claimAdapter.claimSignal(signalId);
            }, executor);
        }

        CompletableFuture.allOf(futures).join();
        executor.shutdown();

        long count = repository.countBySignalId(signalId);
        assertEquals(1, count, "Should only create one claim for the same signalId even in parallel");
    }
}
