package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.tracing.ExecutionContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OrderRepositoryPort {
    Optional<Order> claimForExecution(UUID orderId, ExecutionContext context);
    Optional<Order> claimForExecutionInCurrentTransaction(UUID orderId, ExecutionContext context);
    Optional<Order> claimForReconciliation(UUID orderId);
    Optional<Order> findById(UUID orderId);
    List<Order> findStuckOrdersInStatuses(Set<OrderStatus> statuses, Instant threshold);
    void save(Order order);
}
