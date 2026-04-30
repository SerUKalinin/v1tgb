package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Маппер для преобразования между JPA сущностью OrderEntity и доменной моделью Order.
 */
@Component
public class OrderMapper {

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
                .status(entity.getStatus() != null ? OrderStatus.valueOf(entity.getStatus()) : null)
                .executedQuantity(entity.getExecutedQuantity())
                .averagePrice(entity.getAveragePrice())
                .build();
    }

    public OrderEntity toEntity(Order domain) {
        if (domain == null) {
            return null;
        }

        OrderEntity entity = OrderEntity.builder()
                .id(domain.getId())
                .clientOrderId(domain.getClientOrderId())
                .exchangeOrderId(domain.getExchangeOrderId())
                .symbol(domain.getSymbol())
                .side(domain.getSide())
                .type(domain.getType())
                .quantity(domain.getQuantity())
                .price(domain.getPrice())
                .strategyId(domain.getStrategyId())
                .status(domain.getStatus() != null ? domain.getStatus().name() : null)
                .executedQuantity(domain.getExecutedQuantity())
                .averagePrice(domain.getAveragePrice())
                .build();

        return entity;
    }}
