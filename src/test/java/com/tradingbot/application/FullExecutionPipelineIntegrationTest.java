package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FullExecutionPipelineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ExecutionClaimRepository claimRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private SignalExecutionFacade signalExecutionFacade;

    @Test
    void testFullPipelineIdempotencyAndDeterminism() {
        UUID signalId = UUID.randomUUID();
        SignalEvent signal = new SignalEvent(
                signalId,
                "BTCUSDT",
                SignalType.BUY,
                new BigDecimal("50000"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                "STRAT-1"
        );

        // 1. ПЕРВЫЙ ПРОХОД: Signal -> Order -> Claim -> Execute -> Commit -> Outbox
        signalExecutionFacade.execute(signal);

        OrderEntity order1 = orderRepository.findBySignalId(signalId)
                .orElseThrow(() -> new AssertionError("Order should be created"));

        UUID executionId1 = order1.getExecutionId();
        assertNotNull(executionId1, "ExecutionId must be assigned");
        assertEquals(OrderStatus.FILLED, order1.getStatus());
        assertTrue(claimRepository.existsBySignalId(signalId), "Execution claim must exist");

        long outboxCount1 = outboxRepository.count();
        assertTrue(outboxCount1 > 0, "Outbox should contain events");

        // 2. ПОВТОРНЫЙ ПРОХОД: Тот же сигнал (Idempotency Check)
        signalExecutionFacade.execute(signal);

        // Проверка: дубликатов нет
        assertEquals(1, orderRepository.countBySignalId(signalId), "Should not create duplicate OrderEntity");
        assertEquals(1, claimRepository.countBySignalId(signalId), "Should not create duplicate ExecutionClaim");
        assertEquals(outboxCount1, outboxRepository.count(), "Should not create duplicate Outbox events");

        // 3. ПРОВЕРКА ДЕТЕРМИНИЗМА: executionId должен совпадать при повторной генерации контекста
        // (Проверяется косвенно через отсутствие дублей в Outbox, так как ID события = executionId)

        // 4. ПРОВЕРКА SSOT: CorrelationId и CausationId в Outbox
        outboxRepository.findAll().forEach(event -> {
            assertNotNull(event.getCorrelationId(), "CorrelationId must be present");
            assertNotNull(event.getCausationId(), "CausationId must be present");
            assertEquals(signalId, event.getSignalId(), "SignalId must be preserved in Outbox");
        });
    }
}
