package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import com.tradingbot.infrastructure/persistence/repository/OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrderExecutionIdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private ExecutionClaimRepository claimRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Test
    void testDuplicateOutboxEventDoesNotCreateNewClaim() throws Exception {
        UUID signalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        
        // Создаем фейковое событие Outbox
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId(UUID.randomUUID());
        event.setSignalId(signalId);
        event.setOrderId(orderId);
        event.setAggregateId(orderId);
        event.setEventType("ORDER_CREATED");
        event.setPayload("{}");
        outboxRepository.save(event);

        // 1. Первая обработка (должна пройти или хотя бы создать claim, если ордер существует)
        // В данном тесте мы проверяем именно логику claimOrder
        executionHandler.claimOrder(event);
        
        long countAfterFirst = claimRepository.count();
        
        // 2. Повторная обработка того же события (или события с тем же signalId)
        executionHandler.claimOrder(event);
        
        assertEquals(countAfterFirst, claimRepository.count(), 
            "Second processing of the same signal must not create additional claims");
    }
}
