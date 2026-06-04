package com.tradingbot.infrastructure.persistence;

import com.tradingbot.common.enums.OrderStatus;
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
        
        Order order = Order.builder()
                .id(orderId)
                .signalId(signalId)
                .symbol("BTCUSDT")
                .side(com.tradingbot.common.enums.OrderSide.BUY)
                .type(com.tradingbot.common.enums.OrderType.MARKET)
                .quantity(BigDecimal.ONE)
                .status(OrderStatus.PENDING_EXECUTION)
                .build();

        OrderEntity entity = orderMapper.toEntity(order);
        
        assertNull(entity.getExecutionId(), "ExecutionId should be null for new PENDING order");
        assertEquals(OrderStatus.PENDING_EXECUTION, entity.getStatus());
        assertEquals(orderId, entity.getId());
    }

    @Test
    void testOrderMapperPropagatesExecutionId() {
        UUID orderId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        
        Order order = Order.reconstruct(
                orderId, "client-1", "BTCUSDT", 
                com.tradingbot.common.enums.OrderSide.BUY, 
                com.tradingbot.common.enums.OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.ZERO, "strat-1", UUID.randomUUID(),
                OrderStatus.EXECUTING, 0L, executionId, null, null, null, null, null
        );

        OrderEntity entity = new OrderEntity();
        orderMapper.updateEntity(order, entity);
        
        assertEquals(executionId, entity.getExecutionId(), "Mapper must propagate executionId from domain to entity");
    }
}
