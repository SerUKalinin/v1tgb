package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionOwnershipValidator;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogContext;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbox consumer, отвечающий за исполнение ордера.
 *
 * <p>Является ядром execution pipeline:</p>
 * <ul>
 *     <li>claim execution</li>
 *     <li>отправка ордера на биржу</li>
 *     <li>commit результата исполнения</li>
 *     <li>публикация событий исполнения</li>
 * </ul>
 *
 * <p>Обеспечивает идемпотентность, контроль владения executionId
 * и защиту от повторной обработки событий.</p>
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
     * Репозиторий ордеров.
     */
    private final OrderRepositoryPort orderRepository;

    /**
     * Сервис атомарного claim execution + Order -> EXECUTING.
     */
    private final OrderExecutionClaimService orderExecutionClaimService;

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
     * Логгер execution событий.
     */
    private final ExecutionLogger executionLogger;

    /**
     * JSON mapper для десериализации событий.
     */
    private final ObjectMapper objectMapper;

    /**
     * Обработка события ORDER_CREATED из outbox.
     */
    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }

    /**
     * Выполняет execution lifecycle.
     *
     * <p>Транзакционный claim вынесен в отдельный bean.
     * Поэтому REQUIRES_NEW на OrderExecutionClaimService реально
     * проходит через Spring proxy.</p>
     *
     * <p>После завершения claim transaction внешний exchange I/O
     * выполняется уже вне DB transaction.</p>
     */
    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        OrderCreatedEvent payload =
                objectMapper.readValue(event.getPayload(), OrderCreatedEvent.class);

        ExecutionContext context = ExecutionContext.from(event);
        ExecutionLogContext.load(context);

        try {
            Optional<Order> orderOpt =
                    orderExecutionClaimService.claim(event, context, payload);

            if (orderOpt.isEmpty()) {
                return;
            }

            Order order = orderOpt.get();
            String lockKey = "EXEC_ORDER_" + order.getId();

            executionLogger.log(
                    ExecutionLogFactory.from(
                            order,
                            context,
                            ExecutionEventType.EXECUTION_START,
                            ExecutionStateMapper.toContractState(order.getStatus()),
                            "Order claimed and execution starting"
                    )
            );

            ExecutionResult result;

            try {
                log.info(
                        "[EXECUTION-START] Placing order. Context: {}",
                        context
                );

                result = executionPort.placeOrder(order);

            } catch (Exception e) {
                /*
                 * Внешний exchange I/O находится вне DB transaction.
                 * Не пытаемся здесь менять Order в той же transaction.
                 * Дальнейшее состояние должно быть определено reconciliation.
                 */
                log.error(
                        "[EXECUTION-IO-ERROR] Context: {}. " +
                                "Will be recovered by reconciliation.",
                        context,
                        e
                );
                return;
            }

            try {
                commitExecution(
                        event,
                        context,
                        order,
                        result,
                        lockKey
                );

            } catch (Exception e) {
                log.error(
                        "[EXECUTION-COMMIT-ERROR] Context: {}. " +
                                "Critical inconsistency risk.",
                        context,
                        e
                );
                throw e;
            }

            executionLogger.log(
                    ExecutionLogFactory.from(
                            order,
                            context,
                            ExecutionEventType.EXECUTION_SUCCESS,
                            ExecutionStateMapper.toContractState(order.getStatus()),
                            "Execution committed with status " + order.getStatus()
                    )
            );

        } finally {
            ExecutionLogContext.clear();
        }
    }

    /**
     * Финальный commit результата исполнения ордера.
     *
     * <p>Обновляет агрегат Order, публикует outbox events,
     * фиксирует lock и обеспечивает идемпотентность.</p>
     *
     * <p>ВАЖНО: этот метод пока оставлен здесь без структурного
     * переноса. Следующим отдельным Fix Loop проверим его
     * transaction boundary, потому что self-invocation имеет
     * ту же проблему Spring proxy.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitExecution(
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

    /**
     * Публикация completion event в outbox.
     */
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

    /**
     * Определение типа события завершения исполнения.
     */
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
            case PARTIALLY_FILLED -> "ORDER_PARTIALLY_FILLED";
            case ACCEPTED -> "ORDER_ACCEPTED";
            case REJECTED -> "ORDER_REJECTED";
            case EXCHANGE_STATE_UNKNOWN -> "ORDER_TIMEOUT";
            case CANCELED -> "ORDER_CANCELED";
            default -> "ORDER_COMPLETED";
        };
    }
}