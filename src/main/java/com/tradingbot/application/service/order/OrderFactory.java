package com.tradingbot.application.service.order;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class OrderFactory {

    private final OrderMapper orderMapper;

    public OrderEntity create(Order order) {
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
