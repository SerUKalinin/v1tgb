package com.tradingbot.infrastructure.persistence;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderRejectionReasonIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    @Test
    @Transactional
    void testRejectionReasonIsPreserved() {
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        String reason = "INSUFFICIENT_FUNDS";

        // 1. Создаем ордер
        Order order = Order.createPendingExecution(
                orderId, "client-123", "BTCUSDT",
                com.tradingbot.common.enums.OrderSide.BUY,
                com.tradingbot.common.enums.OrderType.MARKET,
                BigDecimal.ONE, BigDecimal.ZERO, "STRAT-1", signalId
        );

        // 2. Переводим в REJECTED
        order.markAsRejected(ExecutionContext.of(signalId), reason);

        // 3. Сохраняем
        OrderEntity entity = orderMapper.toEntity(order);
        orderRepository.saveAndFlush(entity);

        // 4. Загружаем и проверяем
        OrderEntity savedEntity = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.REJECTED, savedEntity.getStatus());
        assertEquals(reason, savedEntity.getRejectionReason());

        // 5. Проверяем маппинг обратно в домен
        Order reloadedOrder = orderMapper.toDomain(savedEntity);
        assertEquals(reason, reloadedOrder.getRejectionReason());
    }
}
