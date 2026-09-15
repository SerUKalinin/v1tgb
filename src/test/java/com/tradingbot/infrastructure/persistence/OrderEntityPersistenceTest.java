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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderEntityPersistenceTest {

    private final OrderMapper orderMapper = new OrderMapper();

    @Test
    void testOrderEntityPersistsExecutionIdForPendingOrder() {
        UUID orderId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                );

        UUID signalId =
                UUID.fromString(
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                );

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-order-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.001"),
                        new BigDecimal("77000"),
                        "SMA_STUB",
                        signalId
                );

        OrderEntity entity =
                orderMapper.toEntity(order);

        assertNotNull(
                order.getExecutionId(),
                "ExecutionId must be assigned when PENDING_EXECUTION order is created"
        );

        assertNotNull(
                entity.getExecutionId(),
                "OrderEntity must contain executionId for PENDING_EXECUTION order"
        );

        assertEquals(
                order.getExecutionId(),
                entity.getExecutionId(),
                "Mapper must persist the same executionId from Order"
        );

        assertEquals(
                OrderStatus.PENDING_EXECUTION,
                entity.getStatus()
        );

        assertEquals(
                orderId,
                entity.getId()
        );

        assertEquals(
                signalId,
                entity.getSignalId()
        );
    }

    @Test
    void testOrderEntityCanRestoreExecutionId() {
        UUID orderId =
                UUID.fromString(
                        "cccccccc-cccc-cccc-cccc-cccccccccccc"
                );

        UUID signalId =
                UUID.fromString(
                        "dddddddd-dddd-dddd-dddd-dddddddddddd"
                );

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-order-2",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        new BigDecimal("0.002"),
                        new BigDecimal("76000"),
                        "SMA_STUB",
                        signalId
                );

        OrderEntity entity =
                orderMapper.toEntity(order);

        Order restored =
                orderMapper.toDomain(entity);

        assertNotNull(
                restored.getExecutionId(),
                "Restored Order must contain executionId"
        );

        assertEquals(
                order.getExecutionId(),
                restored.getExecutionId(),
                "ExecutionId must survive Order -> Entity -> Order mapping"
        );

        assertEquals(
                order.getId(),
                restored.getId()
        );

        assertEquals(
                order.getSignalId(),
                restored.getSignalId()
        );

        assertEquals(
                OrderStatus.PENDING_EXECUTION,
                restored.getStatus()
        );
    }
}
