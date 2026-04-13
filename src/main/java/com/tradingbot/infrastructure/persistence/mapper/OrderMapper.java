package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class OrderMapper {
    public OrderEntity toEntity(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setQuantity(order.getQuantity());
        entity.setPrice(order.getPrice());
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}