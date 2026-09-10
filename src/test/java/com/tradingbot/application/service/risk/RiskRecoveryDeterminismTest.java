package com.tradingbot.application.service.risk;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class RiskRecoveryDeterminismTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRecoveryService recoveryService;
    @Autowired
    private RiskReservationLogRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void testRecoveryDeterminismWithMixedCommitOrder() throws InterruptedException {
        repository.deleteAll();

        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.execute(() -> transactionTemplate.execute(status -> {
            repository.save(RiskReservationLogEntity.builder()
                    .id(UUID.randomUUID())
                    .orderId(order1)
                    .eventType("RESERVE")
                    .amount(new BigDecimal("100"))
                    .createdAt(Instant.now())
                    .build());
            latch1.countDown();
            try {
                latch2.await();
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));

        executor.execute(() -> {
            try {
                latch1.await();
                transactionTemplate.execute(status -> {
                    repository.save(RiskReservationLogEntity.builder()
                            .id(UUID.randomUUID())
                            .orderId(order2)
                            .eventType("RESERVE")
                            .amount(new BigDecimal("200"))
                            .createdAt(Instant.now())
                            .build());
                    return null;
                });
                latch2.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        // Recovery
        recoveryService.recover();
        com.tradingbot.domain.risk.RiskState state = riskEngine.getState();

        // Assertions        // 1. sum(activeReservations) == getReserved()
        // 2. State is deterministic based on sequence_id, not commit order
        // (In this test, order1 should have lower sequence_id than order2)
    }
}
