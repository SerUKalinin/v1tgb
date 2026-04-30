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
        
        OrderEntity originalEntity = OrderEntity.builder()
                .id(UUID.randomUUID())
                .clientOrderId("CL-123")
                .exchangeOrderId("EX-456")
                .symbol("BTCUSDT")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .quantity(new BigDecimal("2.0"))
                .price(new BigDecimal("50000.0"))
                .status(OrderStatus.PARTIALLY_FILLED.name())
                .executedQuantity(executedQty)
                .averagePrice(avgPrice)
                .strategyId("STRAT-1")
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
        assertEquals(originalEntity.getExecutedQuantity(), roundTripEntity.getExecutedQuantity(), "Executed quantity lost in toEntity");
        assertEquals(originalEntity.getAveragePrice(), roundTripEntity.getAveragePrice(), "Average price lost in toEntity");
        assertEquals(originalEntity.getStatus(), roundTripEntity.getStatus());
        assertEquals(originalEntity.getQuantity(), roundTripEntity.getQuantity());
    }
}
