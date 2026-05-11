package com.tradingbot.application.service.execution;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogContext;
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

/**
 * <h1>OrderExecutionHandler</h1>
 * 
 * <p>Обработчик исполнения ордеров с поддержкой детерминированной идентичности.
 * Реализует восстановление контекста и управление графом причинности (causality).
 */
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
        // Восстановление контекста из Outbox Event (SSOT)
        ExecutionContext context = new ExecutionContext(
                event.getAggregateId(),
                event.getCorrelationId(),
                event.getSignalId(),
                event.getOrderId(),
                event.getExecutionId(),
                event.getId() // causationId = ID текущего события
        );

        ExecutionLogContext.load(context);
        try {
            // PHASE 1: CLAIM (TX1)
            Optional<Order> orderOpt = claimOrder(event);
            if (orderOpt.isEmpty()) {
                return;
            }

            Order order = orderOpt.get();
            
            // INVARIANT: Каждая попытка исполнения получает НОВЫЙ executionId и НОВЫЙ eventId в графе причинности
            UUID executionAttemptId = UUID.randomUUID();
            ExecutionContext execContext = context.startExecutionAttempt(executionAttemptId, event.getId());
            ExecutionLogContext.load(execContext);
            
            String lockKey = "EXEC_ORDER_" + order.getId();

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    execContext,
                    ExecutionEventType.EXECUTION_START,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Order claimed and execution starting"
            ));

            // PHASE 2: EXTERNAL IO (NO TX)
            ExecutionResult result;
            try {
                log.info("[EXECUTION-START] Placing order {} with context {}", order.getId(), execContext);
                result = executionPort.placeOrder(order);
            } catch (Exception e) {
                log.error("[EXECUTION-IO-ERROR] context {}. Will be recovered by reconciliation.", execContext, e);
                return;
            }

            // PHASE 3: COMMIT (TX2)
            try {
                commitExecution(event, execContext, order, result, lockKey);
            } catch (Exception e) {
                log.error("[EXECUTION-COMMIT-ERROR] context {}. Critical inconsistency risk.", execContext, e);
                throw e;
            }

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    execContext,
                    ExecutionEventType.EXECUTION_SUCCESS,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Execution committed with status " + order.getStatus()
            ));
        } finally {
            ExecutionLogContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Optional<Order> claimOrder(OutboxEventEntity event) {
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            return Optional.empty();
        }

        if (stateManager != null && !stateManager.isReady()) {
            throw new IllegalStateException("System not ready for execution");
        }

        // Consistency Root: aggregateId (Signal), но лочим по orderId
        UUID orderId = event.getOrderId();
        if (orderId == null) {
            orderId = event.getAggregateId();
        }
        
        Optional<Order> orderOpt = orderRepository.claimForExecution(orderId);
        
        if (orderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return Optional.empty();
        }

        return orderOpt;
    }

    @Transactional
    public void commitExecution(OutboxEventEntity event, ExecutionContext context, Order order, ExecutionResult result, String lockKey) {
        ExecutionOwnershipValidator.validateExecutionOwnership(order, order.getExecutionId());

        // Генерация ID для события завершения и переход в графе причинности
        UUID completionEventId = UUID.randomUUID();
        ExecutionContext completionContext = context.nextStep(completionEventId);

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
                    completionContext,
                    ExecutionEventType.EXECUTION_FAIL,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Rejected by exchange: " + result.getErrorMessage()
            ));
        } else if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
            order.markAsUnknown();
            log.warn("[EXECUTION-TIMEOUT] Order {} moved to UNKNOWN.", order.getId());

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    completionContext,
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
        
        // Публикация события с обновленным causationId
        outboxService.publishEvent(completionContext, "ORDER", "ORDER_EXECUTED", executedEvent);
        
        idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
        lockService.markExecuted(lockKey);

        log.info("[EXECUTION-SUCCESS] Order {} committed with context {}", order.getId(), completionContext);
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
