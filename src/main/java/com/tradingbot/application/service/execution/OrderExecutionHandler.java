package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.order.OrderCreatedEvent;import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.execution.ExecutionOwnershipValidator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * <h1>OrderExecutionHandler</h1>
 *
 * <p>Обработчик исполнения ордеров с поддержкой детерминированной идентичности.
 * Реализует цикл CLAIM -> EXECUTE -> COMMIT для обеспечения технической идемпотентности.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    private final ExecutionPort executionPort;
    private final OrderRepositoryPort orderRepository;
    private final SystemStateManager stateManager;
    private final ExecutionLockService lockService;
    private final RiskEngine riskEngine;
    private final OutboxService outboxService;
    private final TransitionValidator transitionValidator;
    private final ExecutionLogger executionLogger;
    private final ExecutionClaimPort executionClaimPort;
    private final ObjectMapper objectMapper;

    @Override    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        // Десериализация в строго типизированный record
        OrderCreatedEvent payload = objectMapper.readValue(event.getPayload(), OrderCreatedEvent.class);

        // Восстановление базового контекста из события
        ExecutionContext baseContext = ExecutionContext.from(event);

        // TRANSPORT RETRY: Сохраняем тот же executionId для идемпотентности при повторах из Outbox
        ExecutionContext context = baseContext.withTransportRetry();
        ExecutionLogContext.load(context);
        try {
            // PHASE 1: CLAIM (TX1 - REQUIRES_NEW)
            // Проверяем и фиксируем право на выполнение данной попытки (executionId)
            Optional<Order> orderOpt = claimOrder(event, context, payload);
            if (orderOpt.isEmpty()) {
                return;
            }
            Order order = orderOpt.get();
            String lockKey = "EXEC_ORDER_" + order.getId();

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    context,
                    ExecutionEventType.EXECUTION_START,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Order claimed and execution starting"
            ));

            // PHASE 2: EXTERNAL IO (NO TX)
            // Выполнение сетевого запроса к бирже вне транзакции
            ExecutionResult result;
            try {
                log.info("[EXECUTION-START] Placing order. Context: {}", context);
                result = executionPort.placeOrder(order);
            } catch (Exception e) {
                log.error("[EXECUTION-IO-ERROR] Context: {}. Will be recovered by reconciliation.", context, e);
                return;
            }

            // PHASE 3: COMMIT (TX2)
            // Фиксация результата исполнения в БД
            try {
                commitExecution(event, context, order, result, lockKey);
            } catch (Exception e) {
                log.error("[EXECUTION-COMMIT-ERROR] Context: {}. Critical inconsistency risk.", context, e);
                throw e;
            }

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    context,
                    ExecutionEventType.EXECUTION_SUCCESS,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Execution committed with status " + order.getStatus()
            ));
        } finally {
            ExecutionLogContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Optional<Order> claimOrder(OutboxEventEntity event, ExecutionContext context, OrderCreatedEvent payload) {
        if (stateManager != null && !stateManager.isReady()) {
            throw new IllegalStateException("System not ready for execution");
        }

        UUID executionId = context.attempt().executionId();
        UUID orderId = payload.orderId();
        UUID signalId = payload.signalId();

        if (orderId == null) {
            throw new IllegalStateException("Invariant violation: orderId is null in OrderCreatedEvent");
        }

        if (signalId == null) {
            throw new IllegalStateException("Invariant violation: signalId is null in OrderCreatedEvent. SignalId is required for execution claim.");
        }
        // Техническая идемпотентность: проверка по executionId (eventId-based запрещена)
        if (executionClaimPort.existsByExecutionId(executionId)) {
            log.info("[EXECUTION-SKIP] executionId {} already claimed. Skipping IO.", executionId);
            return Optional.empty();
        }

        log.info("[EXECUTION-CLAIM-START] Attempting to claim execution. executionId={}, signalId={}, orderId={}",
                executionId, signalId, orderId);

        // Фиксируем клейм перед вызовом биржи. signalId обязателен.
        executionClaimPort.claimExecution(executionId, signalId);

        // Блокируем ордер в БД
        Optional<Order> orderOpt = orderRepository.claimForExecution(orderId, context);
        if (orderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return Optional.empty();
        }

        return orderOpt;
    }

    @Transactional
    public void commitExecution(OutboxEventEntity event, ExecutionContext context, Order order, ExecutionResult result, String lockKey) {
        // Проверка владения executionId перед коммитом
        ExecutionOwnershipValidator.validateExecutionOwnership(order, context.attempt().executionId());

        // Переход в графе причинности для события завершения
        UUID completionEventId = IdentityFactory.deriveEventId(context.attempt().executionId(), "execution-completion");
        ExecutionContext completionContext = context.withNextStep(completionEventId);

        if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
            order.fill(
                    context,
                    result.getExchangeOrderId(),
                    result.getExecutedQty(),
                    result.getExecutedPrice()
            );
        } else if (result.getStatus() == ExecutionResult.Status.REJECTED) {
            order.markAsRejected(context, result.getErrorMessage());
        } else if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
            order.markAsUnknown(context);
            log.warn("[EXECUTION-TIMEOUT] Order moved to UNKNOWN. Context: {}", context);
        }

        if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
            order.clearExecutionOwner();
        }

        orderRepository.save(order);

        // Публикация события об исполнении
        outboxService.publishEvent(
                completionContext,
                "ORDER",
                "ORDER_EXECUTED",
                OrderExecutedEvent.from(order)
        );

        lockService.markExecuted(lockKey);
        log.info("[EXECUTION-SUCCESS] Order committed. Context: {}", completionContext);
    }

    private void handleAlreadyProcessed(OutboxEventEntity event) {
        orderRepository.findById(event.getSignalId()).ifPresent(order -> {
            if (transitionValidator.isProcessed(order.getStatus())) {
                log.debug("[EXECUTION-ALREADY-PROCESSED] Order {} already in terminal state {}",
                        order.getId(), order.getStatus());
            }
        });
    }
}
