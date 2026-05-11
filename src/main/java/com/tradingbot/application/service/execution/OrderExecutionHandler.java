package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionOwnershipValidator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tradingbot.domain.event.OrderExecutedEvent;

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
    private final com.tradingbot.tracing.ExecutionLogger executionLogger;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        // PHASE 1: CLAIM (TX1)
        Optional<Order> orderOpt = claimOrder(event);
        if (orderOpt.isEmpty()) {
            return;
        }

        Order order = orderOpt.get();
        UUID executionId = order.getExecutionId();
        String lockKey = "EXEC_ORDER_" + order.getId();

        executionLogger.log(ExecutionLogFactory.from(
                order,
                ExecutionEventType.EXECUTION_START,
                ExecutionStateMapper.toContractState(order.getStatus()),
                "Order claimed and execution starting"
        ));

        // PHASE 2: EXTERNAL IO (NO TX)
        ExecutionResult result;
        try {
            log.info("[EXECUTION-START] Placing order {} with executionId {}", order.getId(), executionId);
            result = executionPort.placeOrder(order);
        } catch (Exception e) {
            log.error("[EXECUTION-IO-ERROR] order {}. Will be recovered by reconciliation.", order.getId(), e);
            // Мы не бросаем исключение здесь, чтобы не откатывать TX1 (которая уже закоммичена)
            // Ордер остается в EXECUTING, его подберет Watchdog/Reconciliation
            return;
        }

        // PHASE 3: COMMIT (TX2)
        try {
            commitExecution(event, order, result, lockKey);
        } catch (Exception e) {
            log.error("[EXECUTION-COMMIT-ERROR] order {}. Critical inconsistency risk.", order.getId(), e);
            throw e;
        }

        executionLogger.log(ExecutionLogFactory.from(
                order,
                ExecutionEventType.EXECUTION_SUCCESS,
                ExecutionStateMapper.toContractState(order.getStatus()),
                "Execution committed with status " + order.getStatus()
        ));
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Optional<Order> claimOrder(OutboxEventEntity event) {
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            return Optional.empty();
        }

        if (stateManager != null && !stateManager.isReady()) {
            throw new IllegalStateException("System not ready for execution");
        }

        UUID orderId = event.getAggregateId();
        // Используем стандартный claimForExecution (он внутри делает REQUIRES_NEW или мы полагаемся на текущий метод)
        Optional<Order> orderOpt = orderRepository.claimForExecution(orderId);
        
        if (orderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return Optional.empty();
        }

        return orderOpt;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void commitExecution(OutboxEventEntity event, Order order, ExecutionResult result, String lockKey) {
        // Повторная валидация владения перед коммитом
        ExecutionOwnershipValidator.validateExecutionOwnership(order, order.getExecutionId());

        if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
            order.fill(
                    result.getExchangeOrderId(),
                    result.getExecutedQty(),
                    result.getExecutedPrice()
            );
        } else if (result.getStatus() == ExecutionResult.Status.REJECTED) {
            order.markAsRejected(result.getErrorMessage());
            riskEngine.release(order.getId());

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    ExecutionEventType.EXECUTION_FAIL,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Rejected by exchange: " + result.getErrorMessage()
            ));
        } else if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
            order.markAsUnknown();
            log.warn("[EXECUTION-TIMEOUT] Order {} moved to UNKNOWN.", order.getId());

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    ExecutionEventType.EXECUTION_FAIL,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Execution timed out"
            ));
        }

        OrderExecutedEvent executedEvent = OrderExecutedEvent.from(order);

        if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
            order.clearExecutionOwner();
        }

        orderRepository.save(order);
        outboxService.publishEvent(order.getId(), "ORDER", "ORDER_EXECUTED", executedEvent);
        idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
        lockService.markExecuted(lockKey);

        log.info("[EXECUTION-SUCCESS] Order {} committed with status {}", order.getId(), order.getStatus());
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