package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderFinancialTest {

    @Test
    @DisplayName("Should correctly calculate cumulative executed quantity and average price after multiple partial fills")
    void partialFillCalculationTest() {
        // Given
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .originalQuantity(new BigDecimal("10.0"))
                .price(new BigDecimal("100.0"))
                .status(OrderStatus.PENDING_EXECUTION)
                .build();

        // When: First fill (2.0 @ 105.0)
        order.markAsPartiallyFilled(new BigDecimal("2.0"), new BigDecimal("105.0"));
        
        // Then
        assertEquals(new BigDecimal("2.0"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("105.000000000000000000"), order.getAveragePrice());
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.getStatus());

        // When: Second fill (3.0 @ 110.0)
        // Total cost = (2 * 105) + (3 * 110) = 210 + 330 = 540
        // Avg price = 540 / 5 = 108
        order.markAsPartiallyFilled(new BigDecimal("3.0"), new BigDecimal("110.0"));
        // Then
        assertEquals(new BigDecimal("5.0"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("108.000000000000000000"), order.getAveragePrice());
        assertEquals(new BigDecimal("5.0"), order.getRemainingQuantity());
    }

    @Test
    @DisplayName("Should throw exception if executed quantity exceeds original")
    void overfillProtectionTest() {
        Order order = Order.builder()
                .originalQuantity(new BigDecimal("1.0"))
                .build();

        assertThrows(IllegalStateException.class, () -> 
            order.markAsPartiallyFilled(new BigDecimal("1.1"), new BigDecimal("100.0"))
        );
    }
    @Test
    @DisplayName("Should use execution price instead of limit price in markAsFilled")
    void markAsFilledPriceTest() {
        Order order = Order.builder()
                .price(new BigDecimal("100.0")) // Limit price
                .build();

        BigDecimal actualPrice = new BigDecimal("102.5");
        order.markAsFilled("EX-1", new BigDecimal("1.0"), actualPrice);

        assertEquals(actualPrice, order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }
}
