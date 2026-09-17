package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogContext;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Outbox consumer, отвечающий за исполнение ордера.
 *
 * Ядро execution pipeline:
 * - claim execution
 * - отправка ордера на биржу
 * - commit результата
 * - публикация completion events
 *
 * Обеспечивает идемпотентность и ownership executionId.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    /**
     * Порт исполнения ордеров
     * на внешней бирже.
     */
    private final ExecutionPort executionPort;

    /**
     * Репозиторий ордеров.
     */
    private final OrderRepositoryPort orderRepository;

    /**
     * Атомарный claim execution +
     * Order -> EXECUTING.
     */
    private final OrderExecutionClaimService
            orderExecutionClaimService;

    /**
     * Сервис блокировок исполнения.
     */
    private final ExecutionLockService lockService;

    /**
     * Компенсация риска.
     *
     * Оставляем dependency, потому что он
     * существует в текущем application wiring.
     */
    private final OrderCompensationService
            orderCompensationService;

    /**
     * Outbox service.
     */
    private final OutboxService outboxService;

    /**
     * Execution logger.
     */
    private final ExecutionLogger executionLogger;

    /**
     * JSON mapper.
     */
    private final ObjectMapper objectMapper;

    /**
     * Отдельный transactional commit boundary.
     */
    private final OrderExecutionCommitService
            orderExecutionCommitService;

    @Override
    public boolean supports(String eventType) {

        return "ORDER_CREATED".equals(eventType);
    }

    /**
     * Выполняет execution lifecycle.
     *
     * Claim transaction завершается до exchange I/O.
     *
     * Exchange I/O выполняется вне DB transaction.
     *
     * Commit выполняется отдельным REQUIRES_NEW boundary.
     */
    @Override
    public void consume(
            OutboxEvent event
    ) throws Exception {

        OrderCreatedEvent payload =
                objectMapper.readValue(
                        event.payload(),
                        OrderCreatedEvent.class
                );

        ExecutionContext context =
                ExecutionContext.from(event);

        ExecutionLogContext.load(context);

        try {

            Optional<Order> orderOpt =
                    orderExecutionClaimService.claim(
                            event,
                            context,
                            payload
                    );

            if (orderOpt.isEmpty()) {
                return;
            }

            Order order =
                    orderOpt.get();

            String lockKey =
                    "EXEC_ORDER_" + order.getId();

            executionLogger.log(
                    ExecutionLogFactory.from(
                            order,
                            context,
                            ExecutionEventType.EXECUTION_START,
                            ExecutionStateMapper.toContractState(
                                    order.getStatus()
                            ),
                            "Order claimed and execution starting"
                    )
            );

            ExecutionResult result;

            try {

                log.info(
                        "[EXECUTION-START] Placing order. Context: {}",
                        context
                );

                result =
                        executionPort.placeOrder(order);

            } catch (Exception e) {

                /*
                 * Exchange I/O выполняется вне DB transaction.
                 *
                 * Здесь не меняем Order.
                 * Состояние определяется reconciliation.
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
                            ExecutionStateMapper.toContractState(
                                    order.getStatus()
                            ),
                            "Execution committed with status " +
                                    order.getStatus()
                    )
            );

        } finally {

            ExecutionLogContext.clear();
        }
    }
}