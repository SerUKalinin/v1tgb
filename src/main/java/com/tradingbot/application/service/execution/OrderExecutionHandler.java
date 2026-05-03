package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
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
    private final ExecutionLockService lockService;
    private final RiskEngine riskEngine;
    private final OutboxService outboxService;
    private final StateTransitionExecutor transitionExecutor;
    private final TransitionValidator transitionValidator;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        // 1. Глобальная идемпотентность
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[EXECUTION] Event {} already processed, skipping", event.getId());
            return;
        }

        if (stateManager != null && !stateManager.isReady()) {
            log.warn("[EXECUTION] System not ready. Skipping aggregate {}", event.getAggregateId());
            return;
        }

        UUID orderId = event.getAggregateId();
        String lockKey = "EXEC_ORDER_" + orderId;

        // 2. Проверка состояния блокировки
        String currentState = lockService.getLockState(lockKey);
        if ("EXECUTED".equals(currentState)) {
            log.info("[EXECUTION] Order {} already EXECUTED, skipping", orderId);
            idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
            return;
        }

        // 3. Атомарный захват права на исполнение
        if (currentState == null && !lockService.tryClaim(lockKey)) {
            log.warn("[EXECUTION] Order {} already claimed by another processor", orderId);
            return;
        }

        // ЭТАП 1: CLAIM (Блокировка и перевод в EXECUTING в БД)
        Optional<ApprovedOrder> approvedOrder = claimOrder(orderId, event.getId());
        if (approvedOrder.isEmpty()) {
            handleAlreadyProcessed(event);
            return;
        }

        // 4. Атомарный переход в состояние EXECUTING (GUARD)
        boolean isNewExecution = lockService.tryEnterExecuting(lockKey);

        UUID executionId = orderRepository.findById(orderId)
                .map(OrderEntity::getExecutionId)
                .orElse(null);

        // ЭТАП 2: EXECUTE (Внешний IO запрос к бирже)
        ExecutionResult result;
        try {
            if (isNewExecution) {
                result = executeExternal(approvedOrder.get());
            } else {
                log.info("[EXECUTION-RECOVERY] Order {} was in EXECUTING state. Checking status...", orderId);
                result = executionPort.getOrderStatus(approvedOrder.get().getClientOrderId());
            }
        } catch (Exception e) {
            log.error("[EXECUTION-FAILED] Network IO error for order {}", orderId, e);
            return;
        }

        // ЭТАП 3: COMMIT (Фиксация результата исполнения)
        try {
            commitExecution(orderId, result, event.getId(), executionId);
            lockService.markExecuted(lockKey);
        } catch (Exception e) {
            log.error("[EXECUTION-COMMIT-FAILED] Failed to commit result for order {}", orderId, e);
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ApprovedOrder> claimOrder(UUID orderId, UUID eventId) {
        return orderRepository.findByIdForUpdate(orderId).flatMap(entity -> {
            Order order = orderMapper.toDomain(entity);
            OrderStatus currentStatus = order.getStatus();

            boolean isPending = currentStatus == OrderStatus.PENDING_EXECUTION;
            boolean isStale = transitionValidator.isStale(currentStatus, entity.getExecutionStartedAt());

            if (!isPending && !isStale) {
                return Optional.empty();
            }

            if (isStale) {
                log.info("[EXECUTION-RECOVERY] Stale order {} found. Checking exchange status...", entity.getClientOrderId());
                try {
                    ExecutionResult status = executionPort.getOrderStatus(entity.getClientOrderId());
                    if (status.getStatus() == ExecutionResult.Status.SUCCESS || status.getStatus() == ExecutionResult.Status.REJECTED) {
                        commitExecution(entity.getId(), status, eventId, entity.getExecutionId());
                        return Optional.empty();
                    }
                } catch (Exception e) {
                    log.error("[EXECUTION-RECOVERY] Failed to check stale order", e);
                    return Optional.empty();
                }
            }

            try {
                transitionExecutor.execute(order, entity, OrderStatus.EXECUTING, () -> {});

                entity.setExecutionId(UUID.randomUUID());
                entity.setExecutionStartedAt(Instant.now());
                entity.setExecutionAttempts(entity.getExecutionAttempts() + 1);
                entity.setUpdatedAt(Instant.now());

                orderRepository.saveAndFlush(entity);
                return Optional.of(mapToApproved(entity, entity.getExecutionId()));
            } catch (IllegalStateException e) {
                log.error("[EXECUTION-POLICY-VIOLATION] {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    private ExecutionResult executeExternal(ApprovedOrder approvedOrder) throws Exception {
        ExecutionResult result = executionPort.placeOrder(approvedOrder);
        if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
            return executionPort.getOrderStatus(approvedOrder.getClientOrderId());
        }
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitExecution(UUID orderId, ExecutionResult result, UUID eventId, UUID executionId) {
        if (idempotencyService.isAlreadyProcessed(eventId)) return;

        OrderEntity entity = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        if (transitionValidator.isTerminal(entity.getStatus())) {
            log.warn("[EXECUTION] Order {} already terminal: {}", orderId, entity.getStatus());
            return;
        }
        if (executionId != null && !executionId.equals(entity.getExecutionId())) {
            log.warn("[EXECUTION] Stale execution ID for order {}", orderId);
            return;
        }

        applyExecutionResult(entity, result);

        if (result.getStatus() == ExecutionResult.Status.REJECTED) {
            riskEngine.release(orderId);
        }

        Order order = orderMapper.toDomain(entity);
        outboxService.publishEvent(order.getId(), "ORDER", "ORDER_EXECUTED", order);
        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
    }

    private void applyExecutionResult(OrderEntity entity, ExecutionResult result) {
        Order order = orderMapper.toDomain(entity);

        OrderStatus targetStatus = OrderStateTransitionPolicy.mapExecutionResult(result.getStatus());
        if (targetStatus == null) {
            targetStatus = order.getStatus();
        }

        transitionExecutor.execute(order, entity, targetStatus, () -> {
            switch (result.getStatus()) {
                case SUCCESS -> order.fill(result.getExchangeOrderId(), result.getExecutedQty(), result.getExecutedPrice());
                case REJECTED -> order.markAsRejected(result.getErrorMessage());
            }
        });

        orderMapper.updateEntity(order, entity);
        entity.setUpdatedAt(Instant.now());
    }

    private void handleAlreadyProcessed(OutboxEventEntity event) {
        orderRepository.findById(event.getAggregateId()).ifPresent(entity -> {
            if (transitionValidator.isProcessed(entity.getStatus())) {
                idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
            }
        });
    }

    private ApprovedOrder mapToApproved(OrderEntity entity, UUID executionId) {
        return ApprovedOrder.builder()
                .orderId(entity.getId())
                .clientOrderId(entity.getClientOrderId())
                .symbol(entity.getSymbol())
                .side(entity.getSide())
                .type(entity.getType())
                .quantity(entity.getQuantity())
                .price(entity.getPrice())
                .strategyId(entity.getStrategyId())
                .approvedAt(entity.getCreatedAt())
                .build();
    }
}
