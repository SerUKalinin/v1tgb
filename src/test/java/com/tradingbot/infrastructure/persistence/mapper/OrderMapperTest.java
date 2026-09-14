package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderMapperTest {

    private final OrderMapper mapper = new OrderMapper();

    @Test
    void shouldPreserveFinancialStateDuringRoundTripMapping() {
        // Given: Entity with partial fill state
        BigDecimal executedQty = new BigDecimal("1.234567890123456789");
        BigDecimal avgPrice = new BigDecimal("50000.987654321098765432");
        UUID orderId = UUID.randomUUID();

        OrderEntity originalEntity = OrderEntity.builder()
                .id(orderId)
                .clientOrderId("CL-123")
                .exchangeOrderId("EX-456")
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .quantity(new BigDecimal("2.0"))
                .price(new BigDecimal("50000.0"))
                .status(OrderStatus.PARTIALLY_FILLED)
                .executedQuantity(executedQty)
                .averagePrice(avgPrice)
                .strategyId("STRAT-1")
                .signalId(UUID.randomUUID())
                .build();

        // When: Entity -> Domain
        Order domain = mapper.toDomain(originalEntity);

        // Then: Domain state is correct
        assertNotNull(domain);
        assertEquals(executedQty, domain.getExecutedQuantity(), "Executed quantity lost in toDomain");
        assertEquals(avgPrice, domain.getAveragePrice(), "Average price lost in toDomain");
        assertEquals(OrderStatus.PARTIALLY_FILLED, domain.getStatus());

        // When: Domain -> Entity
        OrderEntity roundTripEntity = mapper.toEntity(domain);

        // Then: Entity state is preserved (Symmetry)
        assertEquals(originalEntity.getExecutedQuantity(), roundTripEntity.getExecutedQuantity(),
                "Executed quantity lost in toEntity");
        assertEquals(originalEntity.getAveragePrice(), roundTripEntity.getAveragePrice(),
                "Average price lost in toEntity");
        assertEquals(originalEntity.getStatus(), roundTripEntity.getStatus());
        assertEquals(originalEntity.getQuantity(), roundTripEntity.getQuantity());
    }

    @Test
    void shouldKeepExecutionOwnershipDuringMapping() {
        UUID executionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long version = 17L;

        OrderEntity source = OrderEntity.builder()
                .id(orderId)
                .clientOrderId("CL-OWN")
                .symbol("ETHUSDT")
                .side(OrderSide.SELL)
                .type(OrderType.LIMIT)
                .quantity(new BigDecimal("0.5"))
                .price(new BigDecimal("2500"))
                .status(OrderStatus.PENDING_EXECUTION)
                .strategyId("STRAT-OWN")
                .signalId(UUID.randomUUID())
                .executionId(executionId)
                .version(version)
                .build();

        Order domain = mapper.toDomain(source);
        assertEquals(executionId, domain.getExecutionId());
        assertEquals(version, domain.getVersion());

        OrderEntity target = mapper.toEntity(domain);
        assertEquals(executionId, target.getExecutionId());
        assertEquals(version, (long) target.getVersion());
    }

    @Test
    void updateEntityShouldPreserveOwnershipState() {
        UUID executionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long version = 99L;

        Order order = Order.reconstruct(
                orderId,
                "OWN-UPDATE",
                "SOLUSDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("3"),
                new BigDecimal("15"),
                "STRAT-UPDATE",
                UUID.randomUUID(),
                OrderStatus.EXECUTING,
                version,
                java.time.Instant.now(),
                java.time.Instant.now(),
                executionId,
                java.time.Instant.now(),
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                null
        );

        OrderEntity entity = OrderEntity.builder()
                .id(UUID.randomUUID())
                .clientOrderId("OLD-CLIENT")
                .symbol("SOLUSDT")
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(BigDecimal.ONE)
                .status(OrderStatus.PENDING_EXECUTION)
                .strategyId("OLD-STRAT")
                .signalId(UUID.randomUUID())
                .executionId(UUID.randomUUID())
                .version(5L)
                .build();

        mapper.updateEntity(order, entity);

        assertEquals(executionId, entity.getExecutionId());
        assertEquals(order.getStatus(), entity.getStatus());
    }
}
