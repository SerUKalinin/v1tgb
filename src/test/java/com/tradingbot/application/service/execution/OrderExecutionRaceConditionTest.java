package com.tradingbot.application.service.execution;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
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

    @Test
    void testParallelExecutionDoesNotDoubleFill() throws Exception {
        UUID signalId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        
        // 1. Подготовка ордера в состоянии PENDING_EXECUTION
        com.tradingbot.infrastructure.persistence.entity.OrderEntity orderEntity = new com.tradingbot.infrastructure.persistence.entity.OrderEntity();
        orderEntity.setId(orderId);
        orderEntity.setSignalId(signalId);
        orderEntity.setSymbol("BTCUSDT");
        orderEntity.setStatus(OrderStatus.PENDING_EXECUTION);
        orderEntity.setQuantity(BigDecimal.ONE);
        orderEntity.setPrice(new BigDecimal("50000"));
        orderRepository.saveAndFlush(orderEntity);

        // 2. Подготовка события Outbox
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventId(UUID.randomUUID());
        event.setSignalId(signalId);
        event.setOrderId(orderId);
        event.setAggregateId(orderId);
        event.setEventType("ORDER_CREATED");
        event.setPayload("{}");
        outboxRepository.saveAndFlush(event);

        // 3. Параллельный запуск обработки одного и того же события
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CompletableFuture<?>[] futures = new CompletableFuture[threadCount];

        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    executionHandler.consume(event);
                } catch (Exception e) {
                    // Игнорируем ошибки параллелизма, так как они ожидаемы (lock/claim)
                }
            }, executor);
        }

        CompletableFuture.allOf(futures).join();
        executor.shutdown();

        // 4. Проверка: ордер должен быть обработан ровно один раз (или остаться в корректном состоянии)
        com.tradingbot.infrastructure.persistence.entity.OrderEntity finalOrder = orderRepository.findById(orderId).orElseThrow();
        // Если BacktestExecutionEngine работает, статус будет FILLED
        // Главное, что попыток исполнения не должно быть больше, чем нужно (проверка claim)
        assertEquals(1, finalOrder.getExecutionAttempts(), "Should only attempt execution once due to claim logic");
    }
}
