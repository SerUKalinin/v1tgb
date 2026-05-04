package com.tradingbot.infrastructure.outbox;

import com.tradingbot.domain.risk.RiskRepository;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PartialTransactionRollbackSafetyTest {

    @Autowired
    private TransactionalTestService testService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private RiskRepository riskRepository;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public TransactionalTestService transactionalTestService() {
            return new TransactionalTestService();
        }
    }

    public static class TransactionalTestService {
        @Autowired
        private RiskService riskService;
        @Autowired
        private OutboxService outboxService;

        @Transactional
        public void executeWithFailure(UUID orderId, BigDecimal amount) {
            // 1. Резервируем капитал
            riskService.reserve(orderId, amount);

            // 2. Пишем в Outbox
            outboxService.publishEvent(orderId, "ORDER", "RESERVED", "payload");

            // 3. Искусственный сбой
            throw new RuntimeException("Simulated DB failure before commit");
        }
    }

    @Test
    @DisplayName("CRITICAL: Outbox event and Risk state MUST rollback if transaction fails")
    void shouldRollbackEverythingOnFailure() {
        // Given
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");
        
        RiskState initialState = riskRepository.get();
        BigDecimal initialReserved = initialState.getReservedMargin();

        // When
        assertThrows(RuntimeException.class, () -> {
            testService.executeWithFailure(orderId, amount);
        });

        // Then
        // 1. Проверяем Outbox - событий быть не должно
        long outboxCount = outboxRepository.findAll().stream()
                .filter(e -> orderId.equals(e.getAggregateId()))
                .count();
        assertEquals(0, outboxCount, "Outbox event MUST be rolled back");

        // 2. Проверяем Risk State - резерв не должен измениться
        RiskState finalState = riskRepository.get();
        assertEquals(initialReserved.stripTrailingZeros(), 
                     finalState.getReservedMargin().stripTrailingZeros(), 
                     "Risk reservation MUST be rolled back");
    }
}
