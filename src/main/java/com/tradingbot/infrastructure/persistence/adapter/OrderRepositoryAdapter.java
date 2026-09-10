package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Адаптер для работы с репозиторием Order через JPA.
 *
 * <p>Реализует {@link OrderRepositoryPort}, обеспечивая работу с сущностями {@link OrderEntity} и
 * доменной моделью {@link Order}. Поддерживает claim ордеров для исполнения и reconciliation,
 * а также сохранение и поиск ордеров в БД с учетом правил переходов состояний {@link TransitionValidator}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    /** JPA репозиторий для доступа к OrderEntity */
    private final OrderRepository orderRepository;

    /** Маппер для преобразования между сущностью и доменной моделью */
    private final OrderMapper orderMapper;

    /** Валидатор переходов состояний ордера */
    private final TransitionValidator transitionValidator;

    /**
     * Захватывает ордер для исполнения в текущей транзакции.
     *
     * <p>Проверяет состояние ордера, stale состояния и terminal состояния.
     * Если ордер валиден, присваивает executionOwner и помечает как executing.</p>
     *
     * @param orderId идентификатор ордера
     * @param context контекст исполнения
     * @return Optional с захваченным ордером, если он может быть исполнен
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<Order> claimForExecutionInCurrentTransaction(UUID orderId, ExecutionContext context) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {
            if (transitionValidator.isTerminal(entity.getStatus())) {
                return Optional.empty();
            }

            boolean isStale = transitionValidator.isStale(entity.getStatus(), entity.getExecutionStartedAt());
            if (entity.getStatus() != OrderStatus.PENDING_EXECUTION && !isStale) {
                return Optional.empty();
            }

            Order order = orderMapper.toDomain(entity);
            UUID incomingExecutionId = context.attempt().executionId();
            UUID existingExecutionId = order.getExecutionId();
            if (existingExecutionId != null && !existingExecutionId.equals(incomingExecutionId)) {
                log.warn("Execution id mismatch for order {}: existing={}, incoming={}",
                        orderId, existingExecutionId, incomingExecutionId);
            }

            order.assignExecutionOwner(incomingExecutionId);
            order.markExecuting(context);
            orderMapper.updateEntity(order, entity);
            entity.setUpdatedAt(Instant.now());
            orderRepository.saveAndFlush(entity);
            return Optional.of(order);
        });
    }

    /**
     * Захватывает ордер для исполнения в новой транзакции.
     *
     * @param orderId идентификатор ордера
     * @param context контекст исполнения
     * @return Optional с захваченным ордером
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Order> claimForExecution(UUID orderId, ExecutionContext context) {
        return claimForExecutionInCurrentTransaction(orderId, context);
    }

    /**
     * Захватывает ордер для reconciliation в новой транзакции.
     *
     * <p>Reconciliation разрешена только если ордер не выполняется или stale.</p>
     *
     * @param orderId идентификатор ордера
     * @return Optional с ордером для reconciliation
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Order> claimForReconciliation(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {
            if (transitionValidator.isTerminal(entity.getStatus())) {
                return Optional.empty();
            }

            boolean isExecuting = entity.getStatus() == OrderStatus.EXECUTING;
            boolean isStale = transitionValidator.isStale(entity.getStatus(), entity.getExecutionStartedAt());

            if (isExecuting && !isStale) {
                return Optional.empty();
            }

            Order order = orderMapper.toDomain(entity);
            return Optional.of(order);
        });
    }

    /**
     * Сохраняет изменения ордера в новой транзакции.
     *
     * @param order доменный объект ордера
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Order order) {
        OrderEntity entity = orderRepository.findByIdForUpdate(order.getId())
                .orElseThrow(() -> new IllegalStateException("Order lost: " + order.getId()));

        orderMapper.updateEntity(order, entity);
        entity.setUpdatedAt(Instant.now());
        orderRepository.saveAndFlush(entity);
    }

    /**
     * Находит ордер по UUID.
     *
     * @param orderId идентификатор ордера
     * @return Optional с найденным ордером
     */
    @Override
    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId).map(orderMapper::toDomain);
    }

    /**
     * Находит "зависшие" ордера в указанных статусах, старше указанного порога времени.
     *
     * @param statuses набор статусов для поиска
     * @param threshold порог времени для старых ордеров
     * @return список доменных ордеров
     */
    @Override
    @Transactional(readOnly = true)
    public List<Order> findStuckOrdersInStatuses(Set<OrderStatus> statuses, Instant threshold) {
        return orderRepository.findStuckOrdersInStatuses(statuses, threshold).stream()
                .map(orderMapper::toDomain)
                .collect(Collectors.toList());
    }
}