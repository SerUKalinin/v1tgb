package com.tradingbot.application.service.order;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Фабрика для создания persistence-сущностей OrderEntity из доменной модели Order.
 *
 * <p>Инкапсулирует логику маппинга и обогащения сущности системными атрибутами
 * (например, createdAt), которые не являются частью доменной модели.</p>
 *
 * <p>Используется в application-слое для обеспечения единой точки создания
 * OrderEntity и предотвращения дублирования логики маппинга.</p>
 */
@Component
@RequiredArgsConstructor
public class OrderFactory {

    private final OrderMapper orderMapper;

    /**
     * Создаёт OrderEntity из доменного объекта Order.
     *
     * <p>Дополнительно устанавливает системные поля persistence-слоя.</p>
     *
     * @param order доменная модель ордера
     * @return готовая к сохранению JPA сущность
     */
    public OrderEntity create(Order order) {
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}