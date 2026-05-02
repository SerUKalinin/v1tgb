package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class StatePersistenceAdapter {

    private final OrderRepository orderRepository;

    @Transactional
    public void syncAndSave(Order order, OrderEntity entity) {
        entity.setStatus(order.getStatus());
        entity.setExchangeOrderId(order.getExchangeOrderId());        entity.setExecutedQuantity(order.getExecutedQuantity());
        entity.setAveragePrice(order.getAveragePrice());
        orderRepository.saveAndFlush(entity);
    }
}
