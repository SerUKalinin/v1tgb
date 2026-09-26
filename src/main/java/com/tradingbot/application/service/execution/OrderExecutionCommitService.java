package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.execution.ExecutionOwnershipException;
import com.tradingbot.domain.execution.ExecutionOwnershipValidator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional boundary для фиксации результата execution.
 *
 * Контракты:
 * - SYSTEM_CONTRACT.md
 * - STATE_MACHINE_CONTRACT.md
 * - EXECUTION_ENGINE_CONTRACT.md
 *
 * Exchange I/O здесь отсутствует.
 *
 * Нормальный execution:
 *
 * ORDER_CREATED
 *      ↓
 * claim
 *      ↓
 * EXECUTING
 *      ↓
 * exchange I/O
 *      ↓
 * commit()
 *
 * Recovery execution:
 *
 * EXECUTING / UNKNOWN / RECOVERING
 *      ↓
 * exchange reconciliation
 *      ↓
 * commitRecoveredExecution()
 *
 * Recovery НИКОГДА не вызывает placeOrder().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionCommitService {

    private final OrderRepositoryPort orderRepository;
    private final OrderCompensationService orderCompensationService;
    private final OutboxService outboxService;
    private final ExecutionLockService lockService;

    /**
     * Обычный execution commit.
     *
     * В одной transaction:
     * - Order mutation
     * - Risk mutation
     * - Order persistence
     * - completion outbox
     * - execution lock EXECUTED
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void commit(
            OutboxEvent event,
            ExecutionContext context,
            Order order,
            ExecutionResult result,
            String lockKey
    ) {

        validateContextIdentity(
                order,
                context
        );

        ExecutionOwnershipValidator.validateExecutionOwnership(
                order,
                context.attempt().executionId()
        );

        /*
         * Корректный повтор уже завершённого lifecycle —
         * idempotent no-op.
         */
        if (isTerminal(
                order.getStatus()
        )) {

            log.info(
                    "[EXECUTION-IDEMPOTENT-SKIP] " +
                            "Order {} already in terminal state {}. " +
                            "Skipping commit.",
                    order.getId(),
                    order.getStatus()
            );

            return;
        }

        UUID completionEventId =
                IdentityFactory.deriveEventId(
                        context.attempt().executionId(),
                        "execution-completion"
                );

        ExecutionContext completionContext =
                context.withNextStep(
                        completionEventId
                );

        switch (result.getStatus()) {

            case FILLED -> {

                order.fill(
                        context,
                        result.getExchangeOrderId(),
                        result.getExecutedQty(),
                        result.getExecutedPrice()
                );

                orderCompensationService.consumeReservation(
                        order,
                        "Order fully filled"
                );
            }

            case PARTIALLY_FILLED -> {

                order.applyPartialFill(
                        context,
                        result.getExecutedQty(),
                        result.getExecutedPrice()
                );

                orderCompensationService.consumeReservation(
                        order,
                        "Order partially filled"
                );
            }

            case ACCEPTED -> {

                order.markAccepted(
                        context,
                        result.getExchangeOrderId()
                );
            }

            case REJECTED -> {

                order.markAsRejected(
                        context,
                        result.getErrorMessage()
                );

                orderCompensationService.releasePartial(
                        order,
                        order.getExecutedQuantity()
                );
            }

            case CANCELED -> {

                order.markCancelled(
                        context
                );

                orderCompensationService.releasePartial(
                        order,
                        order.getExecutedQuantity()
                );
            }

            case EXCHANGE_STATE_UNKNOWN -> {

                order.markAsUnknown(
                        context
                );
            }
        }

        orderRepository.save(
                order
        );

        publishCompletionEvent(
                completionContext,
                order,
                result
        );

        boolean markedExecuted =
                lockService.markExecuted(
                        lockKey
                );

        if (!markedExecuted) {

            throw new IllegalStateException(
                    "Failed to transition execution lock to EXECUTED. " +
                            "lockKey=" + lockKey +
                            ", orderId=" + order.getId() +
                            ", executionId=" +
                            context.attempt().executionId()
            );
        }

        log.info(
                "[EXECUTION-SUCCESS] Order committed. Context: {}",
                completionContext
        );
    }

    /**
     * Recovery commit после authoritative exchange query.
     *
     * ВАЖНО:
     *
     * - никакого placeOrder();
     * - executionId остаётся тем же;
     * - reconciliation только предоставляет authoritative result;
     * - Order/Risk/Outbox/lock фиксируются одной transaction.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void commitRecoveredExecution(
            ExecutionContext context,
            Order order,
            ExecutionResult result,
            String settlementKey,
            BigDecimal incrementalNotional,
            String lockKey
    ) {

        validateContextIdentity(
                order,
                context
        );

        UUID executionId =
                context.attempt().executionId();

        if (order.getExecutionId() == null) {

            throw new ExecutionOwnershipException(
                    "Recovery requires persisted executionId. " +
                            "orderId=" + order.getId()
            );
        }

        if (!order.getExecutionId().equals(
                executionId
        )) {

            throw new ExecutionOwnershipException(
                    "Recovery execution identity mismatch. " +
                            "orderId=" + order.getId() +
                            ", order.executionId=" +
                            order.getExecutionId() +
                            ", context.executionId=" +
                            executionId
            );
        }

        if (result == null) {

            throw new IllegalArgumentException(
                    "Recovery execution result cannot be null"
            );
        }

        if (lockKey == null
                || lockKey.isBlank()) {

            throw new IllegalArgumentException(
                    "Recovery execution lock key cannot be blank"
            );
        }

        if (incrementalNotional == null) {

            throw new IllegalArgumentException(
                    "Recovery incrementalNotional cannot be null"
            );
        }

        if (incrementalNotional.signum() < 0) {

            throw new IllegalArgumentException(
                    "Recovery incrementalNotional cannot be negative"
            );
        }

        /*
         * Terminal lifecycle is already committed.
         *
         * Не создаём второй completion event.
         * Не повторяем settlement.
         */
        if (isTerminal(
                order.getStatus()
        )) {

            log.info(
                    "[RECON-COMMIT-IDEMPOTENT-SKIP] " +
                            "Order already terminal. " +
                            "orderId={}, status={}, executionId={}",
                    order.getId(),
                    order.getStatus(),
                    executionId
            );

            return;
        }

        log.info(
                "[RECON-COMMIT-START] " +
                        "orderId={}, executionId={}, exchangeStatus={}, " +
                        "settlementKey={}, incrementalNotional={}",
                order.getId(),
                executionId,
                result.getStatus(),
                settlementKey,
                incrementalNotional
        );

        switch (result.getStatus()) {

            case FILLED -> {

                order.forceFill(
                        context,
                        result.getExchangeOrderId(),
                        result.getExecutedQty(),
                        result.getExecutedPrice()
                );

                settleIncrementalIfNeeded(
                        order,
                        incrementalNotional,
                        settlementKey,
                        "Recovery full fill"
                );
            }

            case PARTIALLY_FILLED -> {

                order.applyPartialFill(
                        context,
                        result.getExecutedQty(),
                        result.getExecutedPrice()
                );

                settleIncrementalIfNeeded(
                        order,
                        incrementalNotional,
                        settlementKey,
                        "Recovery partial fill"
                );
            }

            case ACCEPTED -> {

                order.markAccepted(
                        context,
                        result.getExchangeOrderId()
                );
            }

            case REJECTED -> {

                order.markAsRejected(
                        context,
                        result.getErrorMessage()
                );

                orderCompensationService.releasePartial(
                        order,
                        order.getExecutedQuantity()
                );
            }

            case CANCELED -> {

                order.markCancelled(
                        context
                );

                orderCompensationService.releasePartial(
                        order,
                        order.getExecutedQuantity()
                );
            }

            case EXCHANGE_STATE_UNKNOWN -> {

                order.markAsUnknown(
                        context
                );
            }
        }

        orderRepository.save(
                order
        );

        /*
         * Для recovery completion должен использоваться
         * canonical ORDER_EXECUTED event type.
         *
         * Не используем deriveCheckpointEventType():
         * downstream OrderExecutedEventHandler.supports()
         * принимает только exact "ORDER_EXECUTED".
         */
        UUID completionEventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "execution-completion"
                );

        ExecutionContext completionContext =
                context.withNextStep(
                        completionEventId
                );

        publishCompletionEvent(
                completionContext,
                order,
                result
        );

        boolean markedExecuted =
                lockService.markExecuted(
                        lockKey
                );

        if (!markedExecuted) {

            throw new IllegalStateException(
                    "Failed to transition execution lock to EXECUTED " +
                            "during recovery. " +
                            "lockKey=" + lockKey +
                            ", orderId=" + order.getId() +
                            ", executionId=" + executionId
            );
        }

        log.info(
                "[RECON-COMMIT-SUCCESS] " +
                        "Recovered execution committed. " +
                        "orderId={}, executionId={}, finalStatus={}",
                order.getId(),
                executionId,
                order.getStatus()
        );
    }

    private void settleIncrementalIfNeeded(
            Order order,
            BigDecimal incrementalNotional,
            String settlementKey,
            String reason
    ) {

        if (incrementalNotional.signum() == 0) {

            log.info(
                    "[RECON-RISK-SKIP] " +
                            "No incremental settlement required. " +
                            "orderId={}, settlementKey={}",
                    order.getId(),
                    settlementKey
            );

            return;
        }

        if (settlementKey == null
                || settlementKey.isBlank()) {

            throw new IllegalStateException(
                    "Settlement key is required for positive recovery settlement. " +
                            "orderId=" + order.getId() +
                            ", incrementalNotional=" +
                            incrementalNotional
            );
        }

        orderCompensationService.settleIncrementalExecution(
                order,
                incrementalNotional,
                settlementKey,
                reason
        );
    }

    private void publishCompletionEvent(
            ExecutionContext completionContext,
            Order order,
            ExecutionResult result
    ) {

        String eventType =
                resolveCompletionEventType(
                        order,
                        result
                );

        Object payload =
                OrderExecutedEvent.from(
                        order,
                        result.getExchangeTradeId()
                );

        outboxService.publishEvent(
                completionContext,
                "ORDER",
                eventType,
                payload
        );
    }

    /**
     * Canonical downstream event mapping.
     *
     * Именно ORDER_EXECUTED обрабатывается
     * OrderExecutedEventHandler.
     */
    private String resolveCompletionEventType(
            Order order,
            ExecutionResult result
    ) {

        return switch (result.getStatus()) {

            case FILLED ->
                    "ORDER_EXECUTED";

            case PARTIALLY_FILLED ->
                    "ORDER_EXECUTED";

            case ACCEPTED ->
                    "ORDER_ACCEPTED";

            case REJECTED ->
                    "ORDER_REJECTED";

            case EXCHANGE_STATE_UNKNOWN ->
                    "ORDER_TIMEOUT";

            case CANCELED ->
                    "ORDER_CANCELED";

            default ->
                    "ORDER_COMPLETED";
        };
    }

    private void validateContextIdentity(
            Order order,
            ExecutionContext context
    ) {

        if (order == null) {

            throw new ExecutionOwnershipException(
                    "Order must be provided"
            );
        }

        if (context == null) {

            throw new ExecutionOwnershipException(
                    "ExecutionContext must be provided"
            );
        }

        UUID contextSignalId =
                Objects.requireNonNull(
                        context.signalId(),
                        "Context signalId cannot be null"
                );

        UUID contextExecutionId =
                Objects.requireNonNull(
                        context.attempt().executionId(),
                        "Context executionId cannot be null"
                );

        String contextOrderIdValue =
                context.business().orderId();

        UUID contextOrderId;

        try {

            contextOrderId =
                    UUID.fromString(
                            contextOrderIdValue
                    );

        } catch (IllegalArgumentException e) {

            throw new ExecutionOwnershipException(
                    "Identity mismatch for order " +
                            order.getId() +
                            ": context.business.orderId is not a valid UUID: " +
                            contextOrderIdValue
            );
        }

        if (!Objects.equals(
                order.getSignalId(),
                contextSignalId
        )) {

            throw new ExecutionOwnershipException(
                    "Identity mismatch for order " +
                            order.getId() +
                            ": signalId mismatch, " +
                            "order.signalId=" +
                            order.getSignalId() +
                            ", context.signalId=" +
                            contextSignalId
            );
        }

        if (!Objects.equals(
                order.getId(),
                contextOrderId
        )) {

            throw new ExecutionOwnershipException(
                    "Identity mismatch for order " +
                            order.getId() +
                            ": orderId mismatch, " +
                            "order.id=" +
                            order.getId() +
                            ", context.orderId=" +
                            contextOrderId
            );
        }

        if (order.getExecutionId() == null) {

            throw new ExecutionOwnershipException(
                    "Order has no executionId. orderId=" +
                            order.getId()
            );
        }

        if (!Objects.equals(
                order.getExecutionId(),
                contextExecutionId
        )) {

            throw new ExecutionOwnershipException(
                    "Identity mismatch for order " +
                            order.getId() +
                            ": executionId mismatch, " +
                            "order.executionId=" +
                            order.getExecutionId() +
                            ", context.executionId=" +
                            contextExecutionId
            );
        }
    }

    private boolean isTerminal(
            OrderStatus status
    ) {

        return status == OrderStatus.FILLED
                || status == OrderStatus.CANCELED
                || status == OrderStatus.REJECTED
                || status == OrderStatus.ERROR;
    }
}