package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Маппер для преобразования между JPA сущностью OrderEntity и доменной моделью Order.
 * Обеспечивает синхронизацию данных исполнения и счетчиков попыток.
 */
@Component
public class OrderMapper {

    public Order toDomain(OrderEntity entity) {
        if (entity == null) return null;

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
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getExecutionId(),
                entity.getExecutionStartedAt(),
                entity.getExchangeOrderId(),
                entity.getExecutedQuantity(),
                entity.getAveragePrice(),
                entity.getRejectionReason(),
                entity.getExecutionAttempts()
        );
    }

    public OrderEntity toEntity(Order order) {
        if (order == null) return null;

        return OrderEntity.builder()
                .id(order.getId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .type(order.getType())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .strategyId(order.getStrategyId())
                .signalId(order.getSignalId())
                .status(order.getStatus())
                .version(order.getVersion())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .executionId(order.getExecutionId())
                .executionStartedAt(order.getExecutionStartedAt())
                .executionAttempts(order.getExecutionAttempts())
                .exchangeOrderId(order.getExchangeOrderId())
                .executedQuantity(order.getExecutedQuantity() != null ? order.getExecutedQuantity() : BigDecimal.ZERO)
                .averagePrice(order.getAveragePrice() != null ? order.getAveragePrice() : BigDecimal.ZERO)
                .rejectionReason(order.getRejectionReason())
                .build();
    }

    public void updateEntity(Order order, OrderEntity entity) {
        if (order == null || entity == null) return;

        // Обновляем только mutable бизнес-поля
        entity.setStatus(order.getStatus());
        entity.setUpdatedAt(order.getUpdatedAt());
        entity.setExchangeOrderId(order.getExchangeOrderId());
        entity.setExecutionStartedAt(order.getExecutionStartedAt());
        entity.setExecutionAttempts(order.getExecutionAttempts());
        entity.setExecutedQuantity(order.getExecutedQuantity() != null ? order.getExecutedQuantity() : BigDecimal.ZERO);
        entity.setAveragePrice(order.getAveragePrice() != null ? order.getAveragePrice() : BigDecimal.ZERO);
        entity.setRejectionReason(order.getRejectionReason());
    }
}
