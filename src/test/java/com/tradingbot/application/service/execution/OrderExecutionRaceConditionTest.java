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
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderExecutionRaceConditionTest extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testParallelExecutionDoesNotDoubleFill() throws Exception {
        UUID signalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        String clientOrderId = "CL-" + orderId;

        // 1. Ордер через builder (setters на id/signalId/clientOrderId/strategyId заблокированы)
        OrderEntity orderEntity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId(clientOrderId)
                .signalId(signalId)
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .strategyId("test-strat")
                .status(OrderStatus.PENDING_EXECUTION)
                .quantity(BigDecimal.ONE)
                .price(BigDecimal.valueOf(50000))
                .version(0L)
                .executionAttempts(0)
                .build();
        orderRepository.saveAndFlush(orderEntity);

        // 2. Событие Outbox с валидным payload
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

        // 3. Параллельный запуск
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CompletableFuture<?>[] futures = new CompletableFuture[threadCount];

        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    executionHandler.consume(event);
                } catch (Exception e) {
                    // Ошибки параллелизма ожидаемы (lock/claim)
                }
            }, executor);
        }

        CompletableFuture.allOf(futures).join();
        executor.shutdown();

        // 4. Проверка: попытка исполнения — ровно одна
        OrderEntity finalOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, finalOrder.getExecutionAttempts(),
                "Should only attempt execution once due to claim logic");
    }
}
