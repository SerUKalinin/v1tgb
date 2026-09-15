package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.execution.ExecutionOwnershipValidator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Транзакционный boundary для фиксации результата execution.
 *
 * <p>В одной транзакции выполняются:
 * <ul>
 *     <li>проверка ownership executionId;</li>
 *     <li>изменение Order;</li>
 *     <li>сохранение Order;</li>
 *     <li>публикация completion event в transactional outbox;</li>
 *     <li>фиксация execution lock.</li>
 * </ul>
 *
 * <p>Внешний exchange I/O сюда не входит.</p>
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
     * Фиксирует результат исполнения в отдельной новой DB transaction.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void commit(
            OutboxEventEntity event,
            ExecutionContext context,
            Order order,
            ExecutionResult result,
            String lockKey
    ) {
        ExecutionOwnershipValidator.validateExecutionOwnership(
                order,
                context.attempt().executionId()
        );

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
                order.markCancelled(context);

                orderCompensationService.releasePartial(
                        order,
                        order.getExecutedQuantity()
                );
            }

            case EXCHANGE_STATE_UNKNOWN -> {
                order.markAsUnknown(context);
            }
        }

        /*
         * ВАЖНО:
         * OrderRepositoryAdapter.save() должен быть REQUIRED,
         * чтобы save участвовал именно в этой transaction.
         */
        orderRepository.save(order);

        publishCompletionEvent(
                completionContext,
                order,
                result
        );

        lockService.markExecuted(lockKey);

        log.info(
                "[EXECUTION-SUCCESS] Order committed. Context: {}",
                completionContext
        );
    }

    private void publishCompletionEvent(
            ExecutionContext completionContext,
            Order order,
            ExecutionResult result
    ) {
        String eventType = resolveCompletionEventType(
                order,
                result
        );

        Object payload = OrderExecutedEvent.from(
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
            case PARTIALLY_FILLED -> "ORDER_EXECUTED";
            case ACCEPTED -> "ORDER_ACCEPTED";
            case REJECTED -> "ORDER_REJECTED";
            case EXCHANGE_STATE_UNKNOWN -> "ORDER_TIMEOUT";
            case CANCELED -> "ORDER_CANCELED";
            default -> "ORDER_COMPLETED";
        };
    }
}