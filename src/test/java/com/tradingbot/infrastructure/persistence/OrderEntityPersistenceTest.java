package com.tradingbot.infrastructure.persistence;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderEntityPersistenceTest {

    private final OrderMapper orderMapper = new OrderMapper();

    @Test
    void testOrderEntityCanBeCreatedWithoutExecutionIdInPendingState() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();

        Order order = Order.createPendingExecution(
                orderId,
                "bot_" + orderId.toString().replace("-", ""),
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                BigDecimal.ONE,
                new BigDecimal("50000"),
                "strat-1",
                signalId
        );

        OrderEntity entity = orderMapper.toEntity(order);

        assertNull(entity.getExecutionId(), "ExecutionId should be null for new PENDING order");
        assertEquals(OrderStatus.PENDING_EXECUTION, entity.getStatus());
        assertEquals(orderId, entity.getId());
    }

    @Test
    void testOrderMapperPropagatesExecutionId() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();

        Order order = Order.reconstruct(
                orderId,
                "client-1",
                "BTCUSDT",
                OrderSide.BUY,
                OrderType.MARKET,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                "strat-1",
                signalId,
                OrderStatus.EXECUTING,
                0L,
                java.time.Instant.now(),
                java.time.Instant.now(),
                executionId,
                java.time.Instant.now(),
                null,
                null,
                null,
                null,
                0,
                null
        );

        OrderEntity entity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId("client-1")
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(BigDecimal.ONE)
                .status(OrderStatus.PENDING_EXECUTION)
                .strategyId("strat-1")
                .signalId(signalId)
                .build();

        orderMapper.updateEntity(order, entity);

        assertEquals(executionId, entity.getExecutionId(),
                "Mapper must propagate executionId from domain to entity");
    }
}
