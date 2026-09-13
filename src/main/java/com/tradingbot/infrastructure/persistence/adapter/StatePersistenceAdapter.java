package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Адаптер для синхронизации состояния доменной модели Order
 * с JPA сущностью OrderEntity.
 *
 * <p>Используется в механизмах StateTransitionExecutor для
 * обновления Hibernate Managed Entity и сохранения изменений
 * в базу данных.</p>
 */
@Component
@RequiredArgsConstructor
public class StatePersistenceAdapter {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    /**
     * Обновляет поля сущности из доменной модели и сохраняет изменения.
     *
     * <p>Использует Hibernate Managed Entity и flush, чтобы
     * изменения были немедленно видимы в текущей транзакции.</p>
     *
     * @param order доменная модель Order
     * @param entity сущность OrderEntity для синхронизации
     */
    @Transactional
    public void syncAndSave(Order order, OrderEntity entity) {
        orderMapper.updateEntity(order, entity);
        orderRepository.saveAndFlush(entity);
    }
}