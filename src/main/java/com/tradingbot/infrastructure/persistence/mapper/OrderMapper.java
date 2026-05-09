package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.stereotype.Component;

/**
 * Маппер для преобразования между JPA сущностью OrderEntity и доменной моделью Order.
 */
@Component
public class OrderMapper {

    /**
     * Преобразует сущность БД в доменную модель.
     */
    public Order toDomain(OrderEntity entity) {
        if (entity == null) {
            return null;
        }

        return Order.builder()
                .id(entity.getId())
                .clientOrderId(entity.getClientOrderId())
                .exchangeOrderId(entity.getExchangeOrderId())
                .symbol(entity.getSymbol())
                .side(entity.getSide())
                .type(entity.getType())
                .originalQuantity(entity.getQuantity())
                .price(entity.getPrice())
                .strategyId(entity.getStrategyId())
                .signalId(entity.getSignalId())
                .status(entity.getStatus())
                .executionId(entity.getExecutionId())
                .executionStartedAt(entity.getExecutionStartedAt())
                .version(entity.getVersion() != null ? entity.getVersion() : 0L)
                .executedQuantity(entity.getExecutedQuantity())
                .averagePrice(entity.getAveragePrice())
                .build();
    }

    /**
     * Используется ТОЛЬКО для создания новой сущности (CREATE).
     */
    public OrderEntity toEntity(Order order) {
        if (order == null) return null;
        OrderEntity entity = new OrderEntity();
        entity.setId(order.getId());
        entity.setClientOrderId(order.getClientOrderId());
        entity.setExchangeOrderId(order.getExchangeOrderId());
        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setType(order.getType());
        entity.setQuantity(order.getQuantity());
        entity.setPrice(order.getPrice());
        entity.setExecutedQuantity(order.getExecutedQuantity());
        entity.setAveragePrice(order.getAveragePrice());
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
    }
}
