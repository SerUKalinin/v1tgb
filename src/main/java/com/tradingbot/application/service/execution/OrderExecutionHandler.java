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

        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[EXECUTION] Event {} already processed", event.getId());
            return;
        }

        if (stateManager != null && !stateManager.isReady()) {
            throw new IllegalStateException("System not ready for execution");
        }

        UUID orderId = event.getAggregateId();
        String lockKey = "EXEC_ORDER_" + orderId;

        if ("EXECUTED".equals(lockService.getLockState(lockKey))) {
            idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
            return;
        }

        Optional<ApprovedOrder> approvedOrderOpt = claimOrder(orderId);
        if (approvedOrderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return;
        }

        ApprovedOrder approvedOrder = approvedOrderOpt.get();
        boolean isNewExecution = lockService.tryEnterExecuting(lockKey);

        ExecutionResult result;

        try {
            if (isNewExecution) {

                log.info("[EXECUTION-START] Placing order {} (execId: {})",
                        orderId, approvedOrder.getExecutionId());

                result = executionPort.placeOrder(approvedOrder);

                if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
                    result = retryUntilTerminal(approvedOrder);
                }

            } else {

                log.info("[EXECUTION-RECOVERY] Recovering order {}", orderId);

                result = executionPort.getOrderStatus(approvedOrder.getClientOrderId());

                if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
                    result = retryUntilTerminal(approvedOrder);
                }
            }

        } catch (Exception e) {
            log.error("[EXECUTION-IO-ERROR] order {}", orderId, e);
            throw e;
        }

        try {
            commitExecution(orderId, result, event.getId(), approvedOrder.getExecutionId());
            lockService.markExecuted(lockKey);
        } catch (Exception e) {
            log.error("[EXECUTION-COMMIT-ERROR] order {}", orderId, e);
            throw e;
        }
    }

    private ExecutionResult retryUntilTerminal(ApprovedOrder approvedOrder) throws InterruptedException {

        ExecutionResult result;
        int attempts = 0;

        do {
            Thread.sleep(50);

            result = executionPort.getOrderStatus(approvedOrder.getClientOrderId());
            attempts++;

        } while (
                result.getStatus() != ExecutionResult.Status.SUCCESS &&
                        result.getStatus() != ExecutionResult.Status.REJECTED &&
                        attempts < 20
        );

        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ApprovedOrder> claimOrder(UUID orderId) {

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

            if (entity.getExecutionId() != null) {
                return Optional.of(mapToApproved(entity, entity.getExecutionId()));
            }

            Order order = orderMapper.toDomain(entity);
            UUID executionId = UUID.randomUUID();

            transitionExecutor.execute(order, entity, OrderStatus.EXECUTING, () -> {
                entity.setExecutionId(executionId);
                entity.setExecutionStartedAt(Instant.now());
                entity.setExecutionAttempts(entity.getExecutionAttempts() + 1);
                entity.setUpdatedAt(Instant.now());
            });

            orderRepository.saveAndFlush(entity);

            return Optional.of(mapToApproved(entity, executionId));
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitExecution(UUID orderId, ExecutionResult result, UUID eventId, UUID executionId) {
        if (idempotencyService.isAlreadyProcessed(eventId)) return;

        OrderEntity entity = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order lost: " + orderId));

        boolean isStaleExecution = executionId != null && !executionId.equals(entity.getExecutionId());

        if (isStaleExecution &&
                result.getStatus() != ExecutionResult.Status.SUCCESS &&
                result.getStatus() != ExecutionResult.Status.REJECTED) {
            log.warn("[EXECUTION-STALE] Ignoring non-final stale update {}", orderId);
            return;
        }

        // --- УБРАНО: EXECUTING check ---
        applyExecutionResult(entity, result);

        if (result.getStatus() == ExecutionResult.Status.REJECTED) {
            riskEngine.release(orderId);
        }

        Order order = orderMapper.toDomain(entity);
        outboxService.publishEvent(order.getId(), "ORDER", "ORDER_EXECUTED", order);
        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");

        log.info("[EXECUTION-SUCCESS] Order {} committed with status {}", orderId, entity.getStatus());
    }

    private void applyExecutionResult(OrderEntity entity, ExecutionResult result) {

        Order order = orderMapper.toDomain(entity);

        OrderStatus targetStatus =
                OrderStateTransitionPolicy.mapExecutionResult(result.getStatus());

        if (targetStatus == null) return;

        transitionExecutor.execute(order, entity, targetStatus, () -> {

            if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
                order.fill(
                        result.getExchangeOrderId(),
                        result.getExecutedQty(),
                        result.getExecutedPrice()
                );
            } else if (result.getStatus() == ExecutionResult.Status.REJECTED) {
                order.markAsRejected(result.getErrorMessage());
            }
        });

        orderMapper.updateEntity(order, entity);
        entity.setUpdatedAt(Instant.now());
    }

    private void handleAlreadyProcessed(OutboxEventEntity event) {

        orderRepository.findById(event.getAggregateId()).ifPresent(entity -> {
            if (transitionValidator.isProcessed(entity.getStatus())) {
                idempotencyService.markAsProcessed(
                        event.getId(),
                        "OrderExecutionHandler"
                );
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
                .executionId(executionId)
                .build();
    }
}