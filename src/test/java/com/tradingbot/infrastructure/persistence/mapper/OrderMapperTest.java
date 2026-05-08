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
        
        OrderEntity originalEntity = new OrderEntity();
        originalEntity.setId(UUID.randomUUID());
        originalEntity.setClientOrderId("CL-123");
        originalEntity.setExchangeOrderId("EX-456");
        originalEntity.setSymbol("BTCUSDT");
        originalEntity.setSide(OrderSide.BUY);
        originalEntity.setType(OrderType.LIMIT);
        originalEntity.setQuantity(new BigDecimal("2.0"));
        originalEntity.setPrice(new BigDecimal("50000.0"));
        originalEntity.setStatus(OrderStatus.PARTIALLY_FILLED);
        originalEntity.setExecutedQuantity(executedQty);
        originalEntity.setAveragePrice(avgPrice);
        originalEntity.setStrategyId("STRAT-1");

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
        assertEquals(originalEntity.getExecutedQuantity(), roundTripEntity.getExecutedQuantity(), "Executed quantity lost in toEntity");
        assertEquals(originalEntity.getAveragePrice(), roundTripEntity.getAveragePrice(), "Average price lost in toEntity");
        assertEquals(originalEntity.getStatus(), roundTripEntity.getStatus());
        assertEquals(originalEntity.getQuantity(), roundTripEntity.getQuantity());
    }

    @Test
    void shouldKeepExecutionOwnershipDuringMapping() {
        UUID executionId = UUID.randomUUID();
        long version = 17L;

        OrderEntity source = new OrderEntity();
        source.setId(UUID.randomUUID());
        source.setClientOrderId("CL-OWN");
        source.setSymbol("ETHUSDT");
        source.setSide(OrderSide.SELL);
        source.setType(OrderType.LIMIT);
        source.setQuantity(new BigDecimal("0.5"));
        source.setPrice(new BigDecimal("2500"));
        source.setStatus(OrderStatus.PENDING_EXECUTION);
        source.setStrategyId("STRAT-OWN");
        source.setExecutionId(executionId);
        source.setVersion(version);

        Order domain = mapper.toDomain(source);
        assertEquals(executionId, domain.getExecutionId());
        assertEquals(version, domain.getVersion());

        OrderEntity target = mapper.toEntity(domain);
        assertEquals(executionId, target.getExecutionId());
        assertEquals(version, target.getVersion());
    }

    @Test
    void updateEntityShouldPreserveOwnershipState() {
        UUID executionId = UUID.randomUUID();
        long version = 99L;

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .clientOrderId("OWN-UPDATE")
                .symbol("SOLUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .originalQuantity(new BigDecimal("3"))
                .price(new BigDecimal("15"))
                .strategyId("STRAT-UPDATE")
                .executionId(executionId)
                .version(version)
                .status(OrderStatus.EXECUTING)
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .build();

        OrderEntity entity = new OrderEntity();
        entity.setExecutionId(UUID.randomUUID());
        entity.setVersion(5L);

        mapper.updateEntity(order, entity);

        assertEquals(executionId, entity.getExecutionId());
        assertEquals(version, entity.getVersion());
        assertEquals(order.getStatus(), entity.getStatus());
    }
}
