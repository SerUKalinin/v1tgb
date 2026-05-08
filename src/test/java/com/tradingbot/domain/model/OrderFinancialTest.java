package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderFinancialTest {

    @Test
    @DisplayName("Should correctly set executed quantity and price")
    void fillCalculationTest() {

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .clientOrderId("test-" + UUID.randomUUID())
                .symbol("BTCUSDT")
                .originalQuantity(new BigDecimal("10.0"))
                .price(new BigDecimal("100.0"))
                .status(OrderStatus.EXECUTING) // ✔ ВАЖНО: допустимый статус для fill()
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .build();

        order.fill(
                "EX-1",
                new BigDecimal("10.0"),
                new BigDecimal("105.0")
        );

        assertEquals(new BigDecimal("10.0"), order.getExecutedQuantity());
        assertEquals(new BigDecimal("105.0"), order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }

    @Test
    @DisplayName("Should use execution price instead of limit price in fill")
    void markAsFilledPriceTest() {

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .clientOrderId("test-" + UUID.randomUUID())
                .symbol("BTCUSDT")
                .originalQuantity(new BigDecimal("1.0"))
                .price(new BigDecimal("100.0"))
                .status(OrderStatus.EXECUTING) // ✔ критично
                .executedQuantity(BigDecimal.ZERO)
                .averagePrice(BigDecimal.ZERO)
                .build();

        BigDecimal executionPrice = new BigDecimal("102.5");

        order.fill(
                "EX-1",
                new BigDecimal("1.0"),
                executionPrice
        );

        assertEquals(executionPrice, order.getAveragePrice());
        assertEquals(OrderStatus.FILLED, order.getStatus());
    }
}