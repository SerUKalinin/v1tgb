package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderExecutionIdempotencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private ExecutionClaimRepository claimRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testDuplicateOutboxEventDoesNotCreateNewClaim() throws Exception {
        UUID signalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String clientOrderId = "CL-" + orderId;

        // OrderEntity через builder (setters на id/clientOrderId/strategyId/signalId заблокированы)
        OrderEntity orderEntity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId(clientOrderId)
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .strategyId("test-strat")
                .signalId(signalId)
                .quantity(BigDecimal.ONE)
                .price(BigDecimal.valueOf(50000))
                .status(OrderStatus.PENDING_EXECUTION)
                .version(0L)
                .executionAttempts(0)
                .build();
        orderRepository.saveAndFlush(orderEntity);

        OrderCreatedEvent payload = new OrderCreatedEvent(signalId, orderId, executionId);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .aggregateId(orderId)
                .signalId(signalId)
                .orderId(orderId)
                .executionId(executionId)
                .causationId(orderId)
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload(objectMapper.writeValueAsString(payload))
                .status(OutboxStatus.NEW)
                .sequenceNumber(1L)
                .retryCount(0)
                .attemptCount(0)
                .schemaVersion(1)
                .createdAt(Instant.now())
                .build();
        outboxRepository.saveAndFlush(event);

        // 1. Первая обработка
        executionHandler.consume(event);

        long countAfterFirst = claimRepository.count();
        assertTrue(countAfterFirst > 0, "First consume should create a claim");

        // 2. Повторная обработка — claim должен быть идемпотентным
        executionHandler.consume(event);

        assertEquals(countAfterFirst, claimRepository.count(),
                "Second processing of the same signal must not create additional claims");
    }
}
