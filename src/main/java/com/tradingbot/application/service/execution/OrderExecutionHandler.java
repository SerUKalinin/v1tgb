package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.domain.event.OrderEventPayload;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    private final ExecutionPort executionPort;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final SystemStateManager stateManager;
    private final IdempotencyService idempotencyService;
    private final com.tradingbot.domain.risk.RiskEngine riskEngine;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final OutboxEventRepository outboxRepository;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        // 1. Идемпотентность на входе (по eventId)
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[EXECUTION] Event {} already processed, skipping", event.getId());
            return;
        }

        // 2. Проверка состояния системы
        if (stateManager != null && !stateManager.isReady()) {
            log.warn("[EXECUTION] System not ready. Skipping aggregate {}", event.getAggregateId());
            return;
        }

        UUID orderId = event.getAggregateId();

        // ЭТАП 1: CLAIM (в транзакции)
        Optional<OrderEntity> orderOpt = tryClaimOrder(orderId);
        if (orderOpt.isEmpty()) {
            // Если ордер уже в работе или не найден, помечаем событие как обработанное
            // (только если он уже не PENDING_EXECUTION)
            OrderEntity check = orderRepository.findById(orderId).orElse(null);
            if (check != null && !OrderStatus.PENDING_EXECUTION.name().equals(check.getStatus())) {
                markProcessedInNewTransaction(event.getId());
            }
            return;
        }

        OrderEntity entity = orderOpt.get();

        // ЭТАП 2: EXECUTE (БЕЗ транзакции)
        ExecutionResult result;
        try {
            result = execute(mapToApproved(entity));
        } catch (Exception e) {
            log.error("[EXECUTION-FAILED] Network IO error for order {}", orderId, e);
            // В случае сетевой ошибки оставляем ордер в EXECUTING для recovery процесса
            return;
        }

        // ЭТАП 3: COMMIT RESULT (в транзакции)
        try {
            commitResult(entity.getId(), result, event.getId());
        } catch (Exception e) {
            log.error("[EXECUTION-COMMIT-FAILED] Failed to commit result for order {}", orderId, e);
            throw e; // Пробрасываем для retry механизма outbox
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessedInNewTransaction(UUID eventId) {
        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderEntity> tryClaimOrder(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {
            if (!OrderStatus.PENDING_EXECUTION.name().equals(entity.getStatus())) {
                return Optional.empty();
            }
            Order order = orderMapper.toDomain(entity);
            try {
                order.markExecuting();
                OrderEntity updated = orderMapper.toEntity(order);
                entity.setStatus(updated.getStatus());
                entity.setUpdatedAt(Instant.now());
                return Optional.of(orderRepository.saveAndFlush(entity));
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        });
    }
    private ExecutionResult execute(ApprovedOrder approvedOrder) throws Exception {
        ExecutionResult result = executionPort.placeOrder(approvedOrder);
        return result.getStatus() == ExecutionResult.Status.TIMEOUT ?
                executionPort.getOrderStatus(approvedOrder.getClientOrderId()) : result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitResult(UUID orderId, ExecutionResult result, UUID eventId) {
        OrderEntity entity = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found at commit: " + orderId));

        if (idempotencyService.isAlreadyProcessed(eventId)) return;

        applyExecutionResult(entity, result);
        orderRepository.saveAndFlush(entity);

        if (result.getStatus() == ExecutionResult.Status.REJECTED) {
            riskEngine.release(orderId);
        }

        Order order = orderMapper.toDomain(entity);
        saveOutbox(order.getId(), "ORDER", "ORDER_EXECUTED", order);
        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
    }

    private void applyExecutionResult(OrderEntity entity, ExecutionResult result) {
        Order order = orderMapper.toDomain(entity);
        switch (result.getStatus()) {
            case SUCCESS -> order.markAsFilled(result.getExchangeOrderId(), result.getExecutedQty(), result.getExecutedPrice());
            case REJECTED -> order.markAsRejected(result.getErrorMessage());
        }        OrderEntity updated = orderMapper.toEntity(order);
        entity.setStatus(updated.getStatus());
        entity.setExchangeOrderId(updated.getExchangeOrderId());
        entity.setUpdatedAt(Instant.now());
    }

    private void saveOutbox(UUID aggregateId, String aggregateType, String eventType, Order order) {
        try {
            OrderEventPayload payload = OrderEventPayload.builder()
                    .orderId(order.getId()).clientOrderId(order.getClientOrderId())
                    .symbol(order.getSymbol()).quantity(order.getQuantity())
                    .price(order.getPrice()).status(order.getStatus().name())
                    .timestamp(Instant.now()).strategyId(order.getStrategyId()).build();

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(UUID.randomUUID()).aggregateId(aggregateId).aggregateType(aggregateType)
                    .eventType(eventType).payload(objectMapper.writeValueAsString(payload))
                    .status(com.tradingbot.infrastructure.outbox.OutboxStatus.NEW).createdAt(Instant.now()).build();

            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to save outbox", e);
        }
    }

    private ApprovedOrder mapToApproved(OrderEntity entity) {
        return ApprovedOrder.builder()
                .orderId(entity.getId()).clientOrderId(entity.getClientOrderId())
                .symbol(entity.getSymbol()).side(entity.getSide()).type(entity.getType())
                .quantity(entity.getQuantity()).price(entity.getPrice()).strategyId(entity.getStrategyId())
                .approvedAt(entity.getCreatedAt()).build();
    }
}
