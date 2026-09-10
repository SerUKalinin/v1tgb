package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderPort;
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
 *
 * <p>Реализует {@link OrderPort}, обеспечивая взаимодействие между доменной моделью {@link Order}
 * и сущностями {@link OrderEntity} в базе данных. Поддерживает поиск, создание и обновление
 * сущностей через {@link OrderRepository}, при этом корректно обновляя managed-объекты Hibernate.</p>
 */
@Component
@RequiredArgsConstructor
public class JpaOrderAdapter implements OrderPort {

    /** JPA репозиторий для доступа к OrderEntity */
    private final OrderRepository orderRepository;

    /** Маппер для преобразования между сущностью и доменной моделью */
    private final OrderMapper orderMapper;

    /**
     * Находит ордер по его UUID.
     *
     * @param id идентификатор ордера
     * @return Optional с найденным {@link Order} или пустой, если не найден
     */
    @Override
    public Optional<Order> findById(UUID id) {
        return orderRepository.findById(id).map(orderMapper::toDomain);
    }

    /**
     * Находит ордер по clientOrderId.
     *
     * @param clientOrderId идентификатор клиента
     * @return Optional с найденным {@link Order} или пустой, если не найден
     */
    @Override
    public Optional<Order> findByClientOrderId(String clientOrderId) {
        return orderRepository.findByClientOrderId(clientOrderId).map(orderMapper::toDomain);
    }

    /**
     * Находит ордер по UUID с возможностью блокировки записи для обновления.
     *
     * <p>На данный момент используется обычный поиск, при необходимости
     * можно добавить {@code LockModeType.PESSIMISTIC_WRITE} для блокировки Hibernate.</p>
     *
     * @param id идентификатор ордера
     * @return Optional с найденным {@link Order} или пустой, если не найден
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findByIdForUpdate(UUID id) {
        return orderRepository.findById(id).map(orderMapper::toDomain);
    }

    /**
     * Сохраняет или обновляет ордер.
     *
     * <p>Если сущность уже существует в базе — обновляет поля через {@link OrderMapper#updateEntity(Order, OrderEntity)}.
     * Если не существует — создает новую сущность через {@link OrderMapper#toEntity(Order)}.
     * Сохранение через {@link OrderRepository#save(Object)} поддерживает dirty checking для managed entity.</p>
     *
     * @param order доменный объект ордера для сохранения
     * @return сохраненный доменный объект {@link Order}
     */
    @Override
    @Transactional
    public Order save(Order order) {
        OrderEntity entity = orderRepository.findById(order.getId())
                .map(existing -> {
                    orderMapper.updateEntity(order, existing);
                    return existing;
                })
                .orElseGet(() -> orderMapper.toEntity(order));

        OrderEntity saved = orderRepository.save(entity);
        return orderMapper.toDomain(saved);
    }

    /**
     * Возвращает {@link OrderEntity} напрямую из репозитория.
     *
     * <p>Используется, например, в {@code StateTransitionExecutor} для работы с Hibernate managed entity.</p>
     *
     * @param id идентификатор ордера
     * @return Optional с найденной сущностью {@link OrderEntity} или пустой, если не найден
     */
    public Optional<OrderEntity> getEntityById(UUID id) {
        return orderRepository.findById(id);
    }
}