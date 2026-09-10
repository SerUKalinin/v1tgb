package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.execution.ExecutionOwnershipValidator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
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
 * Outbox consumer, отвечающий за исполнение ордера.
 * <p>
 * Является ядром execution pipeline:
 * <ul>
 *   <li>claim execution</li>
 *   <li>отправка ордера на биржу</li>
 *   <li>commit результата исполнения</li>
 *   <li>публикация событий исполнения</li>
 * </ul>
 * <p>
 * Обеспечивает идемпотентность, контроль владения executionId и
 * защиту от повторной обработки событий.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    /**
     * Порт исполнения ордеров на внешней бирже.
     */
    private final ExecutionPort executionPort;

    /**
     * Репозиторий ордеров (порт доменного слоя).
     */
    private final OrderRepositoryPort orderRepository;

    /**
     * Менеджер состояния системы (gating execution).
     */
    private final SystemStateManager stateManager;

    /**
     * Сервис блокировок исполнения.
     */
    private final ExecutionLockService lockService;

    /**
     * Сервис компенсации риска при неуспешном исполнении.
     */
    private final OrderCompensationService orderCompensationService;

    /**
     * Outbox сервис для публикации событий.
     */
    private final OutboxService outboxService;

    /**
     * Валидатор допустимых переходов состояния ордера.
     */
    private final TransitionValidator transitionValidator;

    /**
     * Логгер execution событий.
     */
    private final ExecutionLogger executionLogger;

    /**
     * Порт claim execution (идемпотентное резервирование исполнения).
     */
    private final ExecutionClaimPort executionClaimPort;

    /**
     * JSON mapper для десериализации событий.
     */
    private final ObjectMapper objectMapper;

    /**
     * Обработка события ORDER_CREATED из outbox.
     * <p>
     * Выполняет полный lifecycle исполнения ордера.
     */
    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        OrderCreatedEvent payload = objectMapper.readValue(event.getPayload(), OrderCreatedEvent.class);
        ExecutionContext baseContext = ExecutionContext.from(event);
        ExecutionContext context = baseContext.withTransportRetry();
        ExecutionLogContext.load(context);

        try {
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

            ExecutionResult result;
            try {
                log.info("[EXECUTION-START] Placing order. Context: {}", context);
                result = executionPort.placeOrder(order);
            } catch (Exception e) {
                log.error("[EXECUTION-IO-ERROR] Context: {}. Will be recovered by reconciliation.", context, e);
                return;
            }

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

    /**
     * Claim ордера для исполнения (idempotent + transactional boundary).
     */
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

        if (executionClaimPort.existsByExecutionId(executionId)) {
            log.info("[EXECUTION-SKIP] executionId {} already claimed. Skipping IO.", executionId);
            return Optional.empty();
        }

        log.info("[EXECUTION-CLAIM-START] Attempting to claim execution. executionId={}, signalId={}, orderId={}",
                executionId, signalId, orderId);

        executionClaimPort.claimExecution(executionId, signalId);

        Optional<Order> orderOpt = orderRepository.claimForExecution(orderId, context);
        if (orderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return Optional.empty();
        }

        return orderOpt;
    }

    /**
     * Финальный commit результата исполнения ордера.
     * <p>
     * Обновляет агрегат Order, публикует outbox события,
     * фиксирует lock и обеспечивает идемпотентность.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitExecution(OutboxEventEntity event, ExecutionContext context, Order order, ExecutionResult result, String lockKey) {

        ExecutionOwnershipValidator.validateExecutionOwnership(order, context.attempt().executionId());

        if (order.getStatus() == OrderStatus.FILLED
                || order.getStatus() == OrderStatus.PARTIALLY_FILLED
                || order.getStatus() == OrderStatus.REJECTED
                || order.getStatus() == OrderStatus.CANCELED) {

            log.info("[EXECUTION-IDEMPOTENT-SKIP] Order {} already in terminal state {}. Skipping commit.",
                    order.getId(), order.getStatus());
            return;
        }

        UUID completionEventId = IdentityFactory.deriveEventId(context.attempt().executionId(), "execution-completion");
        ExecutionContext completionContext = context.withNextStep(completionEventId);

        switch (result.getStatus()) {
            case FILLED -> order.fill(context, result.getExchangeOrderId(), result.getExecutedQty(), result.getExecutedPrice());
            case PARTIALLY_FILLED -> order.applyPartialFill(context, result.getExecutedQty(), result.getExecutedPrice());
            case ACCEPTED -> order.markAccepted(context, result.getExchangeOrderId());
            case REJECTED -> {
                order.markAsRejected(context, result.getErrorMessage());
                orderCompensationService.releasePartial(order, order.getExecutedQuantity());
            }
            case CANCELED -> {
                order.markCancelled(context);
                orderCompensationService.releasePartial(order, order.getExecutedQuantity());
            }
            case EXCHANGE_STATE_UNKNOWN -> order.markAsUnknown(context);
        }

        orderRepository.save(order);
        publishCompletionEvent(completionContext, order, result);
        lockService.markExecuted(lockKey);

        log.info("[EXECUTION-SUCCESS] Order committed. Context: {}", completionContext);
    }

    /**
     * Публикация completion event в outbox.
     */
    private void publishCompletionEvent(ExecutionContext completionContext, Order order, ExecutionResult result) {
        String eventType = resolveCompletionEventType(order, result);
        Object payload = OrderExecutedEvent.from(order);
        outboxService.publishEvent(completionContext, "ORDER", eventType, payload);
    }

    /**
     * Определение типа события завершения исполнения.
     */
    private String resolveCompletionEventType(Order order, ExecutionResult result) {
        if (result.getStatus() == ExecutionResult.Status.FILLED) {
            boolean hasRealExecution = order.getExecutedQuantity() != null
                    && order.getAveragePrice() != null;

            if (!hasRealExecution) {
                log.error("[INVARIANT-VIOLATION] FILLED result but no execution data. orderId={}, status={}",
                        order.getId(), order.getStatus());
            }

            return hasRealExecution ? "ORDER_EXECUTED" : "ORDER_COMPLETED";
        }

        return switch (result.getStatus()) {
            case PARTIALLY_FILLED -> "ORDER_PARTIALLY_FILLED";
            case ACCEPTED -> "ORDER_ACCEPTED";
            case REJECTED -> "ORDER_REJECTED";
            case EXCHANGE_STATE_UNKNOWN -> "ORDER_TIMEOUT";
            case CANCELED -> "ORDER_CANCELED";
            default -> "ORDER_COMPLETED";
        };
    }

    /**
     * Обработка случая, когда ордер уже был обработан ранее.
     */
    private void handleAlreadyProcessed(OutboxEventEntity event) {
        orderRepository.findById(event.getSignalId()).ifPresent(order -> {
            if (transitionValidator.isProcessed(order.getStatus())) {
                log.debug("[EXECUTION-ALREADY-PROCESSED] Order {} already in terminal state {}",
                        order.getId(), order.getStatus());
            }
        });
    }
}