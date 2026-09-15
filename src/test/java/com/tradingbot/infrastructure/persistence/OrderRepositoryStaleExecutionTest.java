package com.tradingbot.infrastructure.persistence;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class OrderRepositoryStaleExecutionTest {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldNotMarkOldOrderAsStuckWhenExecutionStartedRecently() {

        Instant now = Instant.now();

        OrderEntity order =
                OrderEntity.builder()
                        .id(UUID.fromString(
                                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                        ))
                        .clientOrderId("WATCHDOG-FRESH-EXECUTION")
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .quantity(new BigDecimal("0.001"))
                        .price(new BigDecimal("100"))
                        .strategyId("TEST")
                        .signalId(UUID.fromString(
                                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                        ))
                        .status(OrderStatus.EXECUTING)
                        .executionStartedAt(
                                now.minusSeconds(10)
                        )
                        .executionAttempts(1)
                        .executedQuantity(BigDecimal.ZERO)
                        .averagePrice(BigDecimal.ZERO)
                        .build();

        orderRepository.saveAndFlush(order);

        /*
         * @PrePersist перезаписывает createdAt,
         * поэтому после сохранения намеренно делаем Order
         * "старым" по дате создания.
         */
        order.setCreatedAt(
                now.minus(1, ChronoUnit.DAYS)
        );

        orderRepository.saveAndFlush(order);

        Instant threshold =
                now.minus(2, ChronoUnit.MINUTES);

        List<OrderEntity> stuckOrders =
                orderRepository.findStuckOrders(
                        OrderStatus.EXECUTING,
                        threshold
                );

        assertTrue(
                stuckOrders.stream()
                        .noneMatch(
                                item -> item.getId()
                                        .equals(order.getId())
                        ),
                """
                Свежий execution не должен считаться зависшим.
                createdAt старый, executionStartedAt свежий.
                """
        );
    }

    @Test
    void shouldReturnExecutionThatIsActuallyStale() {

        Instant now = Instant.now();

        OrderEntity order =
                OrderEntity.builder()
                        .id(UUID.fromString(
                                "cccccccc-cccc-cccc-cccc-cccccccccccc"
                        ))
                        .clientOrderId("WATCHDOG-STALE-EXECUTION")
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .quantity(new BigDecimal("0.001"))
                        .price(new BigDecimal("100"))
                        .strategyId("TEST")
                        .signalId(UUID.fromString(
                                "dddddddd-dddd-dddd-dddd-dddddddddddd"
                        ))
                        .status(OrderStatus.EXECUTING)
                        .executionStartedAt(
                                now.minus(3, ChronoUnit.MINUTES)
                        )
                        .executionAttempts(1)
                        .executedQuantity(BigDecimal.ZERO)
                        .averagePrice(BigDecimal.ZERO)
                        .build();

        orderRepository.saveAndFlush(order);

        Instant threshold =
                now.minus(2, ChronoUnit.MINUTES);

        List<OrderEntity> stuckOrders =
                orderRepository.findStuckOrders(
                        OrderStatus.EXECUTING,
                        threshold
                );

        assertEquals(
                1,
                stuckOrders.stream()
                        .filter(
                                item -> item.getId()
                                        .equals(order.getId())
                        )
                        .count()
        );
    }
}