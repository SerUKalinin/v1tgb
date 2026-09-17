package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Транзакционный сервис захвата execution.
 *
 * Гарантирует, что execution claim и переход Order
 * PENDING_EXECUTION -> EXECUTING выполняются
 * в одной транзакции.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionClaimService {

    private final SystemStateManager stateManager;
    private final ExecutionClaimPort executionClaimPort;
    private final OrderRepositoryPort orderRepository;
    private final TransitionValidator transitionValidator;

    /**
     * Атомарно захватывает execution и переводит Order
     * в EXECUTING.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public Optional<Order> claim(
            OutboxEvent event,
            ExecutionContext context,
            OrderCreatedEvent payload
    ) {

        if (stateManager != null
                && !stateManager.isReady()) {

            throw new IllegalStateException(
                    "System not ready for execution"
            );
        }

        UUID executionId =
                context.attempt().executionId();

        UUID orderId =
                payload.orderId();

        UUID signalId =
                payload.signalId();

        UUID payloadExecutionId =
                payload.executionId();

        if (executionId == null) {

            throw new IllegalStateException(
                    "Invariant violation: executionId is null in ExecutionContext"
            );
        }

        if (orderId == null) {

            throw new IllegalStateException(
                    "Invariant violation: orderId is null in OrderCreatedEvent"
            );
        }

        if (signalId == null) {

            throw new IllegalStateException(
                    "Invariant violation: signalId is null in OrderCreatedEvent. " +
                            "SignalId is required for execution claim."
            );
        }

        if (payloadExecutionId == null) {

            throw new IllegalStateException(
                    "Invariant violation: executionId is null in OrderCreatedEvent"
            );
        }

        if (!executionId.equals(payloadExecutionId)) {

            throw new IllegalStateException(
                    "Identity mismatch in ORDER_CREATED: " +
                            "payload.executionId=" + payloadExecutionId +
                            ", context.executionId=" + executionId +
                            ", orderId=" + orderId
            );
        }

        log.info(
                "[EXECUTION-CLAIM-START] Attempting to claim execution. " +
                        "executionId={}, signalId={}, orderId={}",
                executionId,
                signalId,
                orderId
        );

        /*
         * Сначала блокируем сам Order через SELECT ... FOR UPDATE
         * и атомарно переводим PENDING_EXECUTION -> EXECUTING.
         */
        Optional<Order> orderOpt =
                orderRepository.claimForExecution(
                        orderId,
                        context
                );

        if (orderOpt.isEmpty()) {

            handleAlreadyProcessed(event);

            return Optional.empty();
        }

        /*
         * Order уже успешно захвачен и находится EXECUTING.
         *
         * Execution claim создаётся в той же REQUIRES_NEW transaction.
         */
        executionClaimPort.claimExecution(
                executionId,
                signalId
        );

        return orderOpt;
    }

    private void handleAlreadyProcessed(
            OutboxEvent event
    ) {

        orderRepository
                .findById(event.signalId())
                .ifPresent(order -> {

                    if (transitionValidator.isProcessed(
                            order.getStatus()
                    )) {

                        log.debug(
                                "[EXECUTION-ALREADY-PROCESSED] " +
                                        "Order {} already in processed state {}",
                                order.getId(),
                                order.getStatus()
                        );
                    }
                });
    }
}