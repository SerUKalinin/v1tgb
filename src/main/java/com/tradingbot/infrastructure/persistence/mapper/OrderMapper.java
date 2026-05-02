package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderSnapshot;
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
                .status(entity.getStatus())
                .executedQuantity(entity.getExecutedQuantity())
                .averagePrice(entity.getAveragePrice())
                .build();
    }

    /**
     * Используется ТОЛЬКО для создания новой сущности (CREATE).
     */
    public OrderEntity toEntity(OrderSnapshot snapshot) {
        if (snapshot == null) return null;
        OrderEntity entity = new OrderEntity();
        updateEntityFromSnapshot(snapshot, entity);
        return entity;
    }

    /**
     * Обновляет существующую managed-сущность из снимка состояния (UPDATE).
     * Это предотвращает пересоздание сущности в Hibernate.
     */
    public void updateEntityFromSnapshot(OrderSnapshot snapshot, OrderEntity entity) {
        if (snapshot == null || entity == null) return;

        // Проверка на соответствие ID при обновлении
        if (entity.getId() != null && !entity.getId().equals(snapshot.getId())) {
            throw new IllegalStateException("Cannot update entity with different ID");
        }

        entity.setId(snapshot.getId());
        entity.setClientOrderId(snapshot.getClientOrderId());
        entity.setExchangeOrderId(snapshot.getExchangeOrderId());
        entity.setSymbol(snapshot.getSymbol());
        entity.setSide(snapshot.getSide());
        entity.setType(snapshot.getType());
        entity.setQuantity(snapshot.getQuantity());
        entity.setPrice(snapshot.getPrice());
        entity.setStopLoss(snapshot.getStopLoss());
        entity.setTakeProfit(snapshot.getTakeProfit());
        entity.setExecutedQuantity(snapshot.getExecutedQuantity());
        entity.setAveragePrice(snapshot.getAveragePrice());
        entity.setStrategyId(snapshot.getStrategyId());
        entity.setStatus(snapshot.getStatus());
    }

    public void updateEntity(Order order, OrderEntity entity) {
        if (order == null || entity == null) return;
        entity.setStatus(order.getStatus());
        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setType(order.getType());

        entity.setQuantity(order.getQuantity());
        entity.setPrice(order.getPrice());

        entity.setExecutedQuantity(order.getExecutedQuantity());
        entity.setAveragePrice(order.getAveragePrice());

        entity.setStrategyId(order.getStrategyId());
    }
}
