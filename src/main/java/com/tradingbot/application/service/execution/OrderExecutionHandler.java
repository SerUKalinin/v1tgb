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

    private final OrderExecutionCommitService orderExecutionCommitService;

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
                orderExecutionCommitService.commit(
                        event,
                        context,
                        order,
                        result,
                        lockKey
                );
            } catch (Exception e) {
                log.error(
                        "[EXECUTION-COMMIT-ERROR] Context: {}. Critical inconsistency risk.",
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
}