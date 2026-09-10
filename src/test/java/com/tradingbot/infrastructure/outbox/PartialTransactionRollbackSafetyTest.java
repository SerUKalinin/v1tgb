package com.tradingbot.infrastructure.outbox;

import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
    private RiskStatePort riskStatePort;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public TransactionalTestService transactionalTestService(
                RiskService riskService,
                OutboxService outboxService
        ) {
            return new TransactionalTestService(riskService, outboxService);
        }
    }

    public static class TransactionalTestService {
        private final RiskService riskService;
        private final OutboxService outboxService;

        public TransactionalTestService(RiskService riskService, OutboxService outboxService) {
            this.riskService = riskService;
            this.outboxService = outboxService;
        }

        @Transactional
        public void executeWithFailure(UUID orderId, BigDecimal amount) {
            ExecutionContext context = createContext(orderId);

            // 1. Резервируем капитал
            riskService.reserve(context, amount);

            // 2. Пишем в Outbox
            outboxService.publishEvent(context, "ORDER", "RESERVED", "payload");

            // 3. Искусственный сбой
            throw new RuntimeException("Simulated DB failure before commit");
        }

        private ExecutionContext createContext(UUID orderId) {
            UUID signalId = UUID.randomUUID();
            return new ExecutionContext(
                    IdentityContext.of(signalId),
                    ExecutionAttemptContext.firstAttempt(signalId),
                    BusinessContext.of(orderId.toString())
            );
        }
    }

    @Test
    @DisplayName("CRITICAL: Outbox event and Risk state MUST rollback if transaction fails")
    void shouldRollbackEverythingOnFailure() {
        // Given
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");

        RiskState initialState = riskStatePort.get();
        BigDecimal initialReserved = initialState.getReservedMargin();

        // When
        assertThrows(RuntimeException.class, () -> {
            testService.executeWithFailure(orderId, amount);
        });

        // Then
        // 1. Проверяем Outbox — событий быть не должно
        long outboxCount = outboxRepository.findAll().stream()
                .filter(e -> orderId.equals(e.getAggregateId()))
                .count();
        assertEquals(0, outboxCount, "Outbox event MUST be rolled back");

        // 2. Проверяем Risk State — резерв не должен измениться
        RiskState finalState = riskStatePort.get();
        assertEquals(
                initialReserved != null ? initialReserved.stripTrailingZeros() : BigDecimal.ZERO.stripTrailingZeros(),
                finalState.getReservedMargin() != null ? finalState.getReservedMargin().stripTrailingZeros() : BigDecimal.ZERO.stripTrailingZeros(),
                "Risk reservation MUST be rolled back"
        );
    }
}
