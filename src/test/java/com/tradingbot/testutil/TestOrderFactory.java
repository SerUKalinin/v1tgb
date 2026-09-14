package com.tradingbot.testutil;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TestOrderFactory {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public TestOrderFactory(OrderRepository orderRepository,
                            OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
    }

    public Order create(OrderStatus status) {

        Order order = Order.createPendingExecution(
                UUID.randomUUID(),
                "test-order-" + UUID.randomUUID(),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                BigDecimal.ONE,
                new BigDecimal("10000"),
                "test-suite",
                UUID.randomUUID()
        );

        OrderEntity entity = orderMapper.toEntity(order);

        entity.setStatus(status);
        entity.setExecutionAttempts(0);
        entity.setVersion(0L);
        entity.setExecutionId(null);
        entity.setExecutionStartedAt(null);
        entity.setUpdatedAt(Instant.now());
        entity.setCreatedAt(Instant.now());

        OrderEntity persisted = orderRepository.saveAndFlush(entity);

        return orderMapper.toDomain(persisted);
    }
}