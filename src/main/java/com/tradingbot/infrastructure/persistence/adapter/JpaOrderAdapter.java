package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Order;import com.tradingbot.domain.model.OrderPort;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Адаптер для работы с JPA репозиторием.
 * Реализует OrderPort, обеспечивая обновление существующих managed-сущностей.
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
    @Transactional(readOnly = true)
    public Optional<Order> findByIdForUpdate(UUID id) {
        // На данный момент используем обычный поиск,
        // при необходимости здесь добавляется LockModeType.PESSIMISTIC_WRITE
        return orderRepository.findById(id).map(orderMapper::toDomain);
    }

    @Override
    @Transactional
    public Order save(Order order) {
        // 1. Пытаемся найти существующую сущность в БД (Managed Entity)
        OrderEntity entity = orderRepository.findById(order.getId())
                .map(existing -> {
                    // 2. Если нашли — обновляем её поля напрямую из домена
                    orderMapper.updateEntity(order, existing);
                    return existing;
                })
                .orElseGet(() -> {
                    // 3. Если не нашли — создаем новую
                    return orderMapper.toEntity(order);
                });

        // 4. Сохраняем (для managed entity это вызовет dirty checking, для новой — persist)
        OrderEntity saved = orderRepository.save(entity);

        // 5. Возвращаем доменную модель
        return orderMapper.toDomain(saved);
    }
    /**
     * Возвращает OrderEntity напрямую.
     * Используется в StateTransitionExecutor для работы с Hibernate Managed Entity.
     */
    public Optional<OrderEntity> getEntityById(UUID id) {
        return orderRepository.findById(id);
    }
}
