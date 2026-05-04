package com.tradingbot.application.service.execution;

import com.tradingbot.infrastructure.execution.ExecutionLockEntity;
import com.tradingbot.infrastructure.execution.ExecutionLockRepository;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.annotation.Propagation;import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Детерминированный тест на race condition.
 * Использует DataJpaTest для изоляции только инфраструктуры БД и сервиса блокировок.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ExecutionLockService.class)
@EntityScan(basePackageClasses = ExecutionLockEntity.class)
@EnableJpaRepositories(basePackageClasses = ExecutionLockRepository.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
public class ExecutionLockRaceConditionTest {

    @Autowired
    private ExecutionLockService lockService;
    @Autowired
    private ExecutionLockRepository repository;

    private String lockKey;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        lockKey = "EXEC_ORDER_" + UUID.randomUUID();

        ExecutionLockEntity lock = ExecutionLockEntity.builder()
                .idempotencyKey(lockKey)
                .state("CLAIMED")
                .createdAt(Instant.now())
                .build();
        repository.saveAndFlush(lock);
    }

    @Test
    void onlyOneThreadShouldTransitionToExecuting() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    // Вызов метода с атомарным UPDATE
                    boolean success = lockService.tryEnterExecuting(lockKey);
                    if (success) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Одновременный старт
        finishLatch.await();
        executorService.shutdown();

        // Проверка инварианта: только один поток успешно обновил запись
        assertThat(successCount.get())
                .as("Only one thread must successfully transition state")
                .isEqualTo(1);

        ExecutionLockEntity finalLock = repository.findById(lockKey).orElseThrow();

        assertThat(finalLock.getState())
                .as("Final state must be EXECUTING")
                .isEqualTo("EXECUTING");
    }
}
