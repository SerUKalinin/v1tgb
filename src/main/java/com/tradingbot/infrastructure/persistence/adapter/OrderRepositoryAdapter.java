package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final TransitionValidator transitionValidator;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Order> claimForExecution(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {
            if (transitionValidator.isTerminal(entity.getStatus())) {
                return Optional.empty();
            }

            boolean isStale = transitionValidator.isStale(entity.getStatus(), entity.getExecutionStartedAt());
            if (entity.getStatus() != OrderStatus.PENDING_EXECUTION && !isStale) {
                return Optional.empty();
            }

            Order order = orderMapper.toDomain(entity);
            order.markExecuting();

            entity.setExecutionId(entity.getExecutionId() != null ? entity.getExecutionId() : UUID.randomUUID());
            entity.setExecutionStartedAt(Instant.now());
            entity.setExecutionAttempts(entity.getExecutionAttempts() + 1);
            entity.setUpdatedAt(Instant.now());
            
            orderMapper.updateEntity(order, entity);
            orderRepository.saveAndFlush(entity);

            return Optional.of(order);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(Order order) {
        OrderEntity entity = orderRepository.findByIdForUpdate(order.getId())
                .orElseThrow(() -> new IllegalStateException("Order lost: " + order.getId()));

        orderMapper.updateEntity(order, entity);
        entity.setUpdatedAt(Instant.now());
        orderRepository.saveAndFlush(entity);
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId).map(orderMapper::toDomain);
    }
}