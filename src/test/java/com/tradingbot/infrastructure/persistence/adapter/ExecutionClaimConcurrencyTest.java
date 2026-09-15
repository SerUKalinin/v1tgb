package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(ExecutionClaimAdapter.class)
class ExecutionClaimConcurrencyTest {

    @Autowired
    private ExecutionClaimAdapter claimAdapter;

    @Autowired
    private ExecutionClaimRepository repository;

    @Test
    void testParallelClaimReturnsSameResult() throws Exception {
        UUID signalId = UUID.randomUUID();

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            CompletableFuture<?>[] futures =
                    new CompletableFuture[threadCount];

            for (int i = 0; i < threadCount; i++) {
                futures[i] = CompletableFuture.runAsync(
                        () -> claimAdapter.claimSignal(signalId),
                        executor
                );
            }

            CompletableFuture.allOf(futures).join();

            long count =
                    repository.countBySignalId(signalId);

            assertEquals(
                    1,
                    count,
                    "For the same signalId exactly one execution claim must exist"
            );
        } finally {
            executor.shutdown();
        }
    }
}