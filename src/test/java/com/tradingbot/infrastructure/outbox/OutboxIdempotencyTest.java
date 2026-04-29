package com.tradingbot.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class OutboxIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldProcessEventOnlyOnceUnderConcurrency() throws Exception {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();

        UUID orderId = UUID.randomUUID();
        String clientOrderId = "test_" + orderId;

        // Создаем ордер в БД, так как хендлер будет его искать
        OrderEntity order = OrderEntity.builder()
                .id(orderId)
                .clientOrderId(clientOrderId)
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(new BigDecimal("0.001"))
                .price(new BigDecimal("50000"))
                .strategyId("test-strategy")
                .status("PENDING_EXECUTION")
                .createdAt(Instant.now())
                .build();
        orderRepository.save(order);

        // Создаем событие Outbox с корректным payload
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(orderId)
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload(objectMapper.writeValueAsString(order))
                .status(OutboxStatus.NEW)
                .createdAt(Instant.now())
                .build();
        outboxEventRepository.save(event);

        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    outboxProcessor.processOutbox();
                } catch (Exception ignored) {}
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Проверяем, что событие в Outbox помечено как PROCESSED ровно один раз
        long processedCount = outboxEventRepository.findAll().stream()
                .filter(e -> e.getStatus() == OutboxStatus.PROCESSED)
                .count();

        assertThat(processedCount).isEqualTo(1);
        
        // Проверяем, что статус ордера изменился (значит хендлер отработал)
        OrderEntity updatedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(updatedOrder.getStatus()).isNotEqualTo("PENDING_EXECUTION");
    }
}
