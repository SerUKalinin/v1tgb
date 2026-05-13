package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxIdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Test
    void testDuplicatePublicationDoesNotCreateDuplicateEvents() {
        UUID signalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ExecutionContext context = ExecutionContext.of(signalId)
                .withBusiness(BusinessContext.of(orderId.toString()));

        Order payload = Order.builder()
                .id(orderId)
                .signalId(signalId)
                .symbol("BTCUSDT")
                .quantity(BigDecimal.ONE)
                .build();

        // 1. Первая публикация
        outboxService.publishEvent(context, "ORDER", "ORDER_CREATED", payload);
        long countAfterFirst = outboxRepository.count();

        // 2. Повторная публикация с тем же контекстом (тот же executionId)
        outboxService.publishEvent(context, "ORDER", "ORDER_CREATED", payload);

        assertEquals(countAfterFirst, outboxRepository.count(), 
            "Outbox must not contain duplicate events for the same executionId");
    }
}
