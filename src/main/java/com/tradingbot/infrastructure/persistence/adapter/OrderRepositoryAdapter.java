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
 * <p>Реализует {@link OrderRepositoryPort}, обеспечивая работу с сущностями
 * {@link OrderEntity} и доменной моделью {@link Order}. Поддерживает claim
 * ордеров для исполнения и reconciliation, а также сохранение и поиск
 * ордеров в БД с учетом правил переходов состояний
 * {@link TransitionValidator}.</p>
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
    public Optional<Order> claimForExecutionInCurrentTransaction(
            UUID orderId,
            ExecutionContext context
    ) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {

            if (transitionValidator.isTerminal(entity.getStatus())) {
                return Optional.empty();
            }

            boolean isStale = transitionValidator.isStale(
                    entity.getStatus(),
                    entity.getExecutionStartedAt()
            );

            if (entity.getStatus() != OrderStatus.PENDING_EXECUTION && !isStale) {
                return Optional.empty();
            }

            Order order = orderMapper.toDomain(entity);

            UUID incomingExecutionId = context.attempt().executionId();
            UUID existingExecutionId = order.getExecutionId();

            if (existingExecutionId == null) {
                throw new IllegalStateException(
                        "Order " + orderId + " has no executionId"
                );
            }

            if (!existingExecutionId.equals(incomingExecutionId)) {
                log.warn(
                        "Execution id mismatch for order {}: existing={}, incoming={}",
                        orderId,
                        existingExecutionId,
                        incomingExecutionId
                );
                return Optional.empty();
            }

            order.markExecuting(context);

            orderMapper.updateEntity(order, entity);
            entity.setUpdatedAt(Instant.now());

            orderRepository.saveAndFlush(entity);

            return Optional.of(order);
        });
    }

    /**
     * Захватывает ордер для исполнения в транзакции.
     *
     * @param orderId идентификатор ордера
     * @param context контекст исполнения
     * @return Optional с захваченным ордером
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<Order> claimForExecution(
            UUID orderId,
            ExecutionContext context
    ) {
        return claimForExecutionInCurrentTransaction(orderId, context);
    }

    /**
     * Захватывает ордер для reconciliation в новой транзакции.
     *
     * <p>Для stale EXECUTING и UNKNOWN используются реальные
     * state-machine transitions под PESSIMISTIC_WRITE.</p>
     *
     * <p>Для остальных reconcilable states используется optimistic CAS
     * через @Version.</p>
     *
     * @param orderId идентификатор ордера
     * @return Optional с ордером для reconciliation
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Order> claimForReconciliation(UUID orderId) {

        /*
         * Первый snapshot нужен только для определения текущего lifecycle.
         *
         * Для UNKNOWN и stale EXECUTING ниже выполняется второй запрос
         * под PESSIMISTIC_WRITE.
         */
        Optional<OrderEntity> snapshotOpt =
                orderRepository.findById(orderId);

        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }

        OrderEntity snapshot = snapshotOpt.get();

        if (transitionValidator.isTerminal(snapshot.getStatus())) {
            return Optional.empty();
        }

        boolean isExecuting =
                snapshot.getStatus() == OrderStatus.EXECUTING;

        boolean isStale =
                transitionValidator.isStale(
                        snapshot.getStatus(),
                        snapshot.getExecutionStartedAt()
                );

        /*
         * Активный execution владеет ордером.
         * Reconciliation не имеет права его перехватывать.
         */
        if (isExecuting && !isStale) {
            return Optional.empty();
        }

        /*
         * RECOVERING означает, что reconciliation уже получил
         * ownership этого lifecycle.
         */
        if (snapshot.getStatus() == OrderStatus.RECOVERING) {
            return Optional.empty();
        }

        /*
         * ============================================================
         * 1. EXECUTING + stale
         * ============================================================
         *
         * Для stale EXECUTING используем обычный PESSIMISTIC_WRITE,
         * потому что здесь необходимо повторно проверить stale condition
         * уже под lock.
         *
         * Дальше:
         *
         *     EXECUTING -> UNKNOWN -> RECOVERING
         */
        if (isExecuting && isStale) {

            return orderRepository.findByIdForUpdate(orderId)
                    .flatMap(entity -> {

                        if (transitionValidator.isTerminal(entity.getStatus())) {
                            return Optional.empty();
                        }

                        boolean currentlyExecuting =
                                entity.getStatus() == OrderStatus.EXECUTING;

                        boolean currentlyStale =
                                transitionValidator.isStale(
                                        entity.getStatus(),
                                        entity.getExecutionStartedAt()
                                );

                        /*
                         * Между snapshot и FOR UPDATE другой worker
                         * уже мог изменить lifecycle.
                         */
                        if (!currentlyExecuting || !currentlyStale) {
                            return Optional.empty();
                        }

                        Order order =
                                orderMapper.toDomain(entity);

                        ExecutionContext context =
                                ExecutionContext.of(order);

                        /*
                         * State machine transitions остаются
                         * исключительно через domain model.
                         */
                        order.markAsUnknown(context);
                        order.markRecovering(context);

                        orderMapper.updateEntity(order, entity);
                        entity.setUpdatedAt(Instant.now());

                        orderRepository.saveAndFlush(entity);

                        return Optional.of(order);
                    });
        }

        /*
         * ============================================================
         * 2. UNKNOWN
         * ============================================================
         *
         * Здесь ключевой fix.
         *
         * Вместо обычного findByIdForUpdate(orderId) используется:
         *
         *     findByIdForUpdateAndStatus(orderId, UNKNOWN)
         *
         * Поэтому конкурентный worker после получения lock уже не
         * увидит UNKNOWN, если первый worker успел выполнить:
         *
         *     UNKNOWN -> RECOVERING
         *
         * В результате второй worker получает Optional.empty(),
         * а не пытается обновить устаревшую entity.
         */
        if (snapshot.getStatus() == OrderStatus.UNKNOWN) {

            return orderRepository.findByIdForUpdateAndStatus(
                            orderId,
                            OrderStatus.UNKNOWN
                    )
                    .flatMap(entity -> {

                        /*
                         * На всякий случай повторно проверяем terminal
                         * condition после получения lock.
                         */
                        if (transitionValidator.isTerminal(entity.getStatus())) {
                            return Optional.empty();
                        }

                        /*
                         * Дополнительная защита:
                         * запрос уже фильтровал UNKNOWN, но state machine
                         * не должен полагаться только на SQL predicate.
                         */
                        if (entity.getStatus() != OrderStatus.UNKNOWN) {
                            return Optional.empty();
                        }

                        Order order =
                                orderMapper.toDomain(entity);

                        ExecutionContext context =
                                ExecutionContext.of(order);

                        /*
                         * Единственный допустимый transition:
                         *
                         *     UNKNOWN -> RECOVERING
                         */
                        order.markRecovering(context);

                        orderMapper.updateEntity(order, entity);
                        entity.setUpdatedAt(Instant.now());

                        orderRepository.saveAndFlush(entity);

                        return Optional.of(order);
                    });
        }

        /*
         * ============================================================
         * 3. Обычные reconcilable states
         * ============================================================
         *
         * Здесь ownership фиксируется через атомарный CAS:
         *
         *     version N -> N + 1
         *
         * Первый worker получает updated == 1.
         * Конкурирующие workers получают updated == 0.
         */
        Long expectedVersion = snapshot.getVersion();

        Set<OrderStatus> claimableStatuses =
                transitionValidator.getReconcilableStatuses()
                        .stream()
                        .filter(status ->
                                status != OrderStatus.EXECUTING
                                        && status != OrderStatus.UNKNOWN
                                        && status != OrderStatus.RECOVERING
                        )
                        .collect(Collectors.toSet());

        int updated =
                orderRepository.tryClaimForReconciliation(
                        orderId,
                        expectedVersion,
                        claimableStatuses,
                        Instant.now()
                );

        /*
         * CAS проигран:
         * другой worker уже изменил version.
         */
        if (updated != 1) {
            return Optional.empty();
        }

        /*
         * clearAutomatically=true в @Modifying гарантирует,
         * что здесь не останется устаревшая managed-сущность.
         */
        return orderRepository.findById(orderId)
                .map(orderMapper::toDomain);
    }

    /**
     * Сохраняет изменения ордера в транзакции.
     *
     * @param order доменный объект ордера
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void save(Order order) {

        OrderEntity entity = orderRepository.findByIdForUpdate(order.getId())
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Order lost: " + order.getId()
                        )
                );

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
        return orderRepository.findById(orderId)
                .map(orderMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findOrderIdsByStatusIn(
            Set<OrderStatus> statuses
    ) {
        return orderRepository.findOrderIdsByStatusIn(statuses);
    }

    /**
     * Находит зависшие ордера в указанных статусах,
     * старше указанного порога времени.
     *
     * @param statuses набор статусов для поиска
     * @param threshold порог времени
     * @return список доменных ордеров
     */
    @Override
    @Transactional(readOnly = true)
    public List<Order> findStuckOrdersInStatuses(
            Set<OrderStatus> statuses,
            Instant threshold
    ) {
        return orderRepository.findStuckOrdersInStatuses(
                        statuses,
                        threshold
                )
                .stream()
                .map(orderMapper::toDomain)
                .collect(Collectors.toList());
    }
}