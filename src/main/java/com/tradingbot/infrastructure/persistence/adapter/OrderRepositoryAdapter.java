package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.exception.StaleOrderStateException;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.tracing.ExecutionContext;
import jakarta.persistence.OptimisticLockException;
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

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final TransitionValidator transitionValidator;

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
            orderMapper.updateEntity(order, entity);
            entity.setUpdatedAt(Instant.now());
            orderRepository.saveAndFlush(entity);
            return Optional.of(order);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Order> claimForExecution(UUID orderId, ExecutionContext context) {
        return claimForExecutionInCurrentTransaction(orderId, context);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Order> claimForReconciliation(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {
            if (transitionValidator.isTerminal(entity.getStatus())) {
                return Optional.empty();
            }

            // Reconciliation разрешена только если ордер НЕ находится в активном исполнении
            // или если исполнение зависло (stale)
            boolean isExecuting = entity.getStatus() == OrderStatus.EXECUTING;
            boolean isStale = transitionValidator.isStale(entity.getStatus(), entity.getExecutionStartedAt());

            if (isExecuting && !isStale) {
                return Optional.empty();
            }

            Order order = orderMapper.toDomain(entity);
            
            // Reconciliation НЕ меняет executionId, она использует существующий или работает без него
            // Но она должна гарантировать, что никто другой не мутирует ордер сейчас
            return Optional.of(order);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Order order) {        OrderEntity entity = orderRepository.findByIdForUpdate(order.getId())
                .orElseThrow(() -> new IllegalStateException("Order lost: " + order.getId()));

        orderMapper.updateEntity(order, entity);
        entity.setUpdatedAt(Instant.now());
        orderRepository.saveAndFlush(entity);
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId).map(orderMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findStuckOrdersInStatuses(Set<OrderStatus> statuses, Instant threshold) {
        return orderRepository.findStuckOrdersInStatuses(statuses, threshold).stream()
                .map(orderMapper::toDomain)
                .collect(Collectors.toList());
    }
}