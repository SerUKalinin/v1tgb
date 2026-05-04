package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
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
    private final OrderRepositoryPort orderRepository;
    private final SystemStateManager stateManager;
    private final IdempotencyService idempotencyService;
    private final ExecutionLockService lockService;
    private final RiskEngine riskEngine;
    private final OutboxService outboxService;
    private final TransitionValidator transitionValidator;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
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

        Optional<Order> orderOpt = orderRepository.claimForExecution(orderId);
        if (orderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return;
        }

        Order order = orderOpt.get();
        if (transitionValidator.isTerminal(order.getStatus())) {
            return;
        }

        boolean isNewExecution = lockService.tryEnterExecuting(lockKey);
        ExecutionResult result;

        try {
            if (isNewExecution) {
                order.markExecuting();
                orderRepository.save(order);

                log.info("[EXECUTION-START] Placing order {}", orderId);
                result = executionPort.placeOrder(order);
                if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
                    result = retryUntilTerminal(order);
                }
            } else {
                log.info("[EXECUTION-RECOVERY] Recovering order {}", orderId);
                result = executionPort.getOrderStatus(order.getClientOrderId());
                if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
                    result = retryUntilTerminal(order);
                }
            }
        } catch (Exception e) {
            log.error("[EXECUTION-IO-ERROR] order {}", orderId, e);
            throw e;
        }

        try {
            if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
                order.fill(
                        result.getExchangeOrderId(),
                        result.getExecutedQty(),
                        result.getExecutedPrice()
                );
            } else if (result.getStatus() == ExecutionResult.Status.REJECTED) {
                order.markAsRejected(result.getErrorMessage());
                riskEngine.release(orderId);
            }

            orderRepository.save(order);
            outboxService.publishEvent(order.getId(), "ORDER", "ORDER_EXECUTED", order);
            idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
            lockService.markExecuted(lockKey);

            log.info("[EXECUTION-SUCCESS] Order {} committed with status {}", orderId, order.getStatus());
        } catch (Exception e) {
            log.error("[EXECUTION-COMMIT-ERROR] order {}", orderId, e);
            throw e;
        }
    }

    private ExecutionResult retryUntilTerminal(Order order) throws InterruptedException {
        ExecutionResult result;
        int attempts = 0;
        do {
            Thread.sleep(50);
            result = executionPort.getOrderStatus(order.getClientOrderId());
            attempts++;
        } while (result.getStatus() == ExecutionResult.Status.TIMEOUT && attempts < 20);
        return result;
    }

    private void handleAlreadyProcessed(OutboxEventEntity event) {
        orderRepository.findById(event.getAggregateId()).ifPresent(order -> {
            if (transitionValidator.isProcessed(order.getStatus())) {
                idempotencyService.markAsProcessed(
                        event.getId(),
                        "OrderExecutionHandler"
                );
            }
        });
    }
}