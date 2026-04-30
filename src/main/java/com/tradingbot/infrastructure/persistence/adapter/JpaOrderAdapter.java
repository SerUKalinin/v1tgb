package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderPort;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Адаптер для работы с JPA репозиторием.
 * Реализует OrderPort, используя OrderMapper для преобразования данных.
 */
@Component
@RequiredArgsConstructor
public class JpaOrderAdapter implements OrderPort {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    @Override
    public Optional<Order> findById(UUID id) {
        return orderRepository.findById(id).map(orderMapper::toDomain);
    }

    @Override
    public Optional<Order> findByClientOrderId(String clientOrderId) {
        return orderRepository.findByClientOrderId(clientOrderId).map(orderMapper::toDomain);
    }

    @Override
    public Order save(Order order) {
        OrderEntity entity = orderMapper.toEntity(order);
        OrderEntity saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
    }

    @Override
    public Optional<Order> findByIdForUpdate(UUID id) {
        return orderRepository.findByIdForUpdate(id).map(orderMapper::toDomain);
    }
}
