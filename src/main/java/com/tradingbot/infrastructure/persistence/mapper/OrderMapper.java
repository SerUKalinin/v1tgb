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
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getExecutionId(),
                entity.getExecutionStartedAt(),                entity.getExchangeOrderId(),
                entity.getExecutedQuantity(),
                entity.getAveragePrice(),
                entity.getRejectionReason(),
                entity.getExecutionAttempts() // Добавлено восстановление попыток
        );
    }

    /**
     * Создает новую сущность на основе доменной модели.
     */
    public OrderEntity toEntity(Order order) {
        if (order == null) return null;

        OrderEntity entity = new OrderEntity();
        updateEntity(order, entity);

        // Поля, которые задаются только при создании
        entity.setId(order.getId());
        entity.setClientOrderId(order.getClientOrderId());
        entity.setStrategyId(order.getStrategyId());
        entity.setSignalId(order.getSignalId());

        return entity;
    }

    /**
     * Обновляет существующую сущность данными из доменной модели.
     */
    public void updateEntity(Order order, OrderEntity entity) {
        if (order == null || entity == null) return;

        // Strict validation
        if (order.getSymbol() == null) throw new IllegalStateException("Order symbol is null for order: " + order.getId());
        if (order.getSide() == null) throw new IllegalStateException("Order side is null for order: " + order.getId());
        if (order.getType() == null) throw new IllegalStateException("Order type is null for order: " + order.getId());

        // Состояние и версия
        entity.setStatus(order.getStatus());
        entity.setVersion(order.getVersion());
        entity.setCreatedAt(order.getCreatedAt());
        entity.setUpdatedAt(order.getUpdatedAt());

        // Параметры ордера
        entity.setSymbol(order.getSymbol());
        entity.setSide(order.getSide());
        entity.setType(order.getType());
        entity.setQuantity(order.getQuantity());
        entity.setPrice(order.getPrice());

        // Данные исполнения
        entity.setExecutionId(order.getExecutionId());
        entity.setExecutionStartedAt(order.getExecutionStartedAt());
        entity.setExecutionAttempts(order.getExecutionAttempts()); // Синхронизация попыток
        entity.setExchangeOrderId(order.getExchangeOrderId());

        // Финансовые показатели (с защитой от null)
        entity.setExecutedQuantity(order.getExecutedQuantity() != null ?
                order.getExecutedQuantity() : BigDecimal.ZERO);
        entity.setAveragePrice(order.getAveragePrice() != null ?
                order.getAveragePrice() : BigDecimal.ZERO);

        entity.setRejectionReason(order.getRejectionReason());
    }}
