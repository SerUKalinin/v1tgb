package com.tradingbot.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.execution.OrderExecutionHandler;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class OutboxDuplicateDeliveryTest extends BaseIntegrationTest {

    @Autowired
    private OrderExecutionHandler executionHandler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExecutionPort executionPort;

    @MockBean
    private SystemStateManager stateManager;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        outboxRepository.deleteAll();
        when(stateManager.getState()).thenReturn(SystemStateManager.SystemState.TRADING_ENABLED);
    }

    @Test
    void shouldCallExchangeOnlyOnceOnDuplicateOutboxEvent() throws Exception {
        // 1. Setup: Create an order and a corresponding outbox event
        UUID orderId = UUID.randomUUID();
        String clientOrderId = "bot_" + orderId.toString().replace("-", "");
        
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
                .build();        orderRepository.save(order);

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(orderId)
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload(objectMapper.writeValueAsString(order))
                .status(OutboxStatus.NEW)
                .createdAt(Instant.now())
                .build();
        outboxRepository.save(event);

        // Mock successful execution
        when(executionPort.placeOrder(any())).thenReturn(
                ExecutionResult.builder()
                        .orderId(orderId)
                        .exchangeOrderId("EX123")
                        .executedQty(new BigDecimal("0.001"))
                        .status(ExecutionResult.Status.SUCCESS)
                        .build()
        );

        // 2. Act: Process the same event twice (simulating duplicate delivery)
        executionHandler.consume(event);
        executionHandler.consume(event);

        // 3. Assert: Exchange should be called exactly once
        verify(executionPort, times(1)).placeOrder(any());
        
        // Order should be in FILLED status
        OrderEntity updatedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo("FILLED");
    }
}
