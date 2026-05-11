package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Маппер для преобразования между JPA сущностью OrderEntity и доменной моделью Order.
 */@Component
public class OrderMapper {

    /**
     * Преобразует сущность БД в доменную модель.
     */
    public Order toDomain(OrderEntity entity) {
        if (entity == null) {
            return null;
        }

        return Order.reconstruct(
                entity.getId(),
                entity.getClientOrderId(),
                entity.getSymbol(),
                entity.getSide(),
                entity.getType(),
                entity.getQuantity(),
                entity.getPrice(),
                entity.getStrategyId(),
                entity.getSignalId(),
                entity.getStatus(),
                entity.getVersion() != null ? entity.getVersion() : 0L,
                entity.getExecutionId(),
                entity.getExecutionStartedAt(),
                entity.getExchangeOrderId(),
                entity.getExecutedQuantity(),
                entity.getAveragePrice(),
                null
        );
    }

    public OrderEntity toEntity(Order order) {
        if (order == null) return null;
        OrderEntity entity = new OrderEntity();
        entity.setId(order.getId());
        entity.setClientOrderId(order.getClientOrderId());
        entity.setExchangeOrderId(order.getExchangeOrderId());
        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setType(order.getType());
        entity.setStatus(order.getStatus());
        entity.setQuantity(order.getQuantity());
        entity.setPrice(order.getPrice());
        entity.setExecutedQuantity(order.getExecutedQuantity() != null ? order.getExecutedQuantity() : BigDecimal.ZERO);
        entity.setAveragePrice(order.getAveragePrice() != null ? order.getAveragePrice() : BigDecimal.ZERO);
        entity.setStrategyId(order.getStrategyId());
        entity.setSignalId(order.getSignalId());
        entity.setExecutionId(order.getExecutionId());
        entity.setVersion(order.getVersion());
        return entity;
    }

    public void updateEntity(Order order, OrderEntity entity) {
        if (order == null || entity == null) return;
        entity.setStatus(order.getStatus());
        entity.setExchangeOrderId(order.getExchangeOrderId());
        entity.setExecutionId(order.getExecutionId());
        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setType(order.getType());

        entity.setQuantity(order.getQuantity());
        entity.setPrice(order.getPrice());

        entity.setExecutedQuantity(order.getExecutedQuantity());
        entity.setAveragePrice(order.getAveragePrice());

        entity.setStrategyId(order.getStrategyId());
        entity.setSignalId(order.getSignalId());
        entity.setVersion(order.getVersion());
    }}
