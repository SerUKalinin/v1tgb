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
    @DisplayName("Should correctly set executed quantity and price")
    void fillCalculationTest() {
        // Given
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .originalQuantity(new BigDecimal("10.0"))
                .price(new BigDecimal("100.0"))
                .status(OrderStatus.PENDING_EXECUTION)
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .build();

        // When
        order.fill("EX-1", new BigDecimal("10.0"), new BigDecimal("105.0"));
        
        // Then
        assertEquals(new BigDecimal("10.0"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("105.0"), order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }

    @Test
    @DisplayName("Should use execution price instead of limit price in fill")
    void markAsFilledPriceTest() {
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .originalQuantity(new BigDecimal("1.0"))
                .price(new BigDecimal("100.0")) // Limit price
                .build();

        BigDecimal actualPrice = new BigDecimal("102.5");
        order.fill("EX-1", new BigDecimal("1.0"), actualPrice);

        assertEquals(actualPrice, order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }}
