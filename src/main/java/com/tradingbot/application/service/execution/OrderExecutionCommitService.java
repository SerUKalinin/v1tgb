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
 * Транзакционный boundary для фиксации результата execution.
 *
 * В одной транзакции выполняются:
 * - проверка полного execution identity;
 * - проверка ownership executionId;
 * - изменение Order;
 * - сохранение Order;
 * - публикация completion event в transactional outbox;
 * - фиксация execution lock.
 *
 * Внешний exchange I/O сюда не входит.
 *
 * Identity SSOT:
 *
 * signalId
 *     ↓
 * orderId
 *     ↓
 * executionId
 *
 * ExecutionContext должен относиться именно к тому
 * persisted Order, который передан в commit().
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
     * Фиксирует результат исполнения
     * в отдельной новой DB transaction.
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

        /*
         * ============================================================
         * CANONICAL IDENTITY GUARD
         * ============================================================
         *
         * Проверяем полный identity ДО:
         *
         * - state-machine transition;
         * - изменения Order;
         * - persistence;
         * - outbox publication;
         * - execution lock.
         *
         * Это исключает ситуацию, когда одинаковый executionId
         * используется вместе с чужим signalId/orderId.
         */
        validateContextIdentity(
                order,
                context
        );

        /*
         * Проверка execution ownership остаётся отдельным
         * domain/application invariant:
         *
         * Order.executionId == Context.executionId
         */
        ExecutionOwnershipValidator.validateExecutionOwnership(
                order,
                context.attempt().executionId()
        );

        /*
         * Terminal/idempotent result.
         *
         * Identity уже проверен выше.
         * Поэтому корректный повтор того же lifecycle
         * остаётся безопасным no-op.
         */
        if (order.getStatus() == OrderStatus.FILLED
                || order.getStatus() == OrderStatus.PARTIALLY_FILLED
                || order.getStatus() == OrderStatus.REJECTED
                || order.getStatus() == OrderStatus.CANCELED) {

            log.info(
                    "[EXECUTION-IDEMPOTENT-SKIP] Order {} already in terminal state {}. " +
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
                context.withNextStep(completionEventId);

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

        /*
         * REQUIRED transaction boundary:
         * OrderRepositoryAdapter.save() участвует
         * в этой transaction.
         */
        orderRepository.save(
                order
        );

        publishCompletionEvent(
                completionContext,
                order,
                result
        );

        lockService.markExecuted(
                lockKey
        );

        log.info(
                "[EXECUTION-SUCCESS] Order committed. Context: {}",
                completionContext
        );
    }

    /**
     * Проверяет согласованность полного identity:
     *
     * persisted Order
     *      ↕
     * ExecutionContext
     *
     * Проверяем:
     *
     *     signalId
     *     orderId
     *     executionId
     *
     * Этот guard намеренно выполняется до state transition,
     * поэтому чужой context никогда не сможет дойти
     * до OrderStateTransitionPolicy.
     */
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

        if (!order.getSignalId().equals(
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

        if (!order.getId().equals(
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

        if (!order.getExecutionId().equals(
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

    private void publishCompletionEvent(
            ExecutionContext completionContext,
            Order order,
            ExecutionResult result
    ) {

        String eventType =
                resolveCompletionEventType(
                        completionContext,
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

    private String resolveCompletionEventType(
            ExecutionContext context,
            Order order,
            ExecutionResult result
    ) {

        if (result.getStatus()
                == ExecutionResult.Status.FILLED
                || result.getStatus()
                == ExecutionResult.Status.PARTIALLY_FILLED) {

            BigDecimal quantity =
                    order.getExecutedQuantity();

            BigDecimal price =
                    order.getAveragePrice();

            if (quantity == null
                    || quantity.signum() <= 0
                    || price == null
                    || price.signum() <= 0) {

                throw new IllegalStateException(
                        "Execution completion event requires valid cumulative " +
                                "quantity and price. orderId=" +
                                order.getId()
                );
            }

            String checkpointKey =
                    result.getStatus().name()
                            + ":"
                            + normalize(
                            quantity
                    )
                            + "@"
                            + normalize(
                            price
                    );

            return IdentityFactory.deriveCheckpointEventType(
                    context.attempt().executionId(),
                    "ORDER_EXECUTED",
                    checkpointKey
            );
        }

        return switch (result.getStatus()) {

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

    private String normalize(
            BigDecimal value
    ) {

        return value
                .stripTrailingZeros()
                .toPlainString();
    }

    private String resolveCompletionEventType(
            Order order,
            ExecutionResult result
    ) {

        if (result.getStatus() == ExecutionResult.Status.FILLED) {

            boolean hasRealExecution =
                    order.getExecutedQuantity() != null
                            && order.getAveragePrice() != null;

            if (!hasRealExecution) {

                log.error(
                        "[INVARIANT-VIOLATION] FILLED result but no execution data. " +
                                "orderId={}, status={}",
                        order.getId(),
                        order.getStatus()
                );
            }

            return hasRealExecution
                    ? "ORDER_EXECUTED"
                    : "ORDER_COMPLETED";
        }

        return switch (result.getStatus()) {

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
}