package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
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
 * <p>
 * Гарантирует атомарность:
 *
 * <pre>
 * Order:
 * PENDING_EXECUTION -> EXECUTING
 *
 * +
 *
 * execution claim
 *
 * +
 *
 * execution lock:
 * CLAIMED -> EXECUTING
 *
 * COMMIT
 * </pre>
 *
 * <p>
 * Внешний exchange I/O выполняется только после завершения
 * этой transaction.
 * </p>
 *
 * Identity SSOT:
 *
 * <pre>
 * signalId
 *     ↓
 * orderId
 *     ↓
 * executionId
 * </pre>
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
     * Технический execution lock.
     */
    private final ExecutionLockService executionLockService;

    /**
     * Атомарно захватывает execution lifecycle.
     *
     * <p>
     * Все DB операции claim находятся в одной REQUIRES_NEW
     * transaction:
     *
     * <pre>
     * PENDING_EXECUTION
     *       ↓
     * Order -> EXECUTING
     *       +
     * execution claim
     *       +
     * execution lock -> EXECUTING
     *       ↓
     * COMMIT
     * </pre>
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

        /*
         * ============================================================
         * CANONICAL EXECUTION IDENTITY CONSISTENCY
         * ============================================================
         *
         * Проверяем:
         *
         *     signalId
         *         ↓
         *     orderId
         *         ↓
         *     executionId
         *
         * до любых execution side effects.
         */

        UUID contextSignalId =
                context.signalId();

        if (!signalId.equals(
                contextSignalId
        )) {

            throw new IllegalStateException(
                    "Identity mismatch in ORDER_CREATED: " +
                            "payload.signalId=" + signalId +
                            ", context.signalId=" + contextSignalId +
                            ", orderId=" + orderId
            );
        }

        String contextOrderIdValue =
                context.business().orderId();

        UUID contextOrderId;

        try {

            contextOrderId =
                    UUID.fromString(
                            contextOrderIdValue
                    );

        } catch (IllegalArgumentException e) {

            throw new IllegalStateException(
                    "Identity mismatch in ORDER_CREATED: " +
                            "context.business.orderId is not a valid UUID: " +
                            contextOrderIdValue,
                    e
            );
        }

        if (!orderId.equals(
                contextOrderId
        )) {

            throw new IllegalStateException(
                    "Identity mismatch in ORDER_CREATED: " +
                            "payload.orderId=" + orderId +
                            ", context.business.orderId=" +
                            contextOrderId +
                            ", signalId=" + signalId
            );
        }

        if (!executionId.equals(
                payloadExecutionId
        )) {

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
         * ============================================================
         * ORDER CLAIM
         * ============================================================
         *
         * PENDING_EXECUTION -> EXECUTING.
         */
        Optional<Order> orderOpt =
                orderRepository.claimForExecution(
                        orderId,
                        context
                );

        if (orderOpt.isEmpty()) {

            handleAlreadyProcessed(
                    event
            );

            return Optional.empty();
        }

        Order order =
                orderOpt.get();

        /*
         * ============================================================
         * EXECUTION CLAIM
         * ============================================================
         *
         * Создаётся в той же outer transaction.
         */
        executionClaimPort.claimExecution(
                executionId,
                signalId
        );

        /*
         * ============================================================
         * EXECUTION LOCK
         * ============================================================
         *
         * Ключ должен совпадать с тем,
         * который OrderExecutionHandler передаёт
         * OrderExecutionCommitService:
         *
         *     EXEC_ORDER_<orderId>
         *
         * claimForExecution() выполняется в той же transaction,
         * поэтому:
         *
         * Order EXECUTING
         * execution claim
         * execution lock EXECUTING
         *
         * либо COMMIT вместе,
         * либо ROLLBACK вместе.
         */
        String lockKey =
                "EXEC_ORDER_" + orderId;

        boolean lockClaimed =
                executionLockService.claimForExecution(
                        lockKey
                );

        if (!lockClaimed) {

            throw new IllegalStateException(
                    "Execution lock could not be claimed for " +
                            "execution lifecycle. " +
                            "orderId=" + orderId +
                            ", executionId=" + executionId +
                            ", lockKey=" + lockKey
            );
        }

        log.info(
                "[EXECUTION-CLAIM-SUCCESS] Execution claimed. " +
                        "executionId={}, orderId={}, lockKey={}, " +
                        "orderStatus={}",
                executionId,
                orderId,
                lockKey,
                order.getStatus()
        );

        return Optional.of(
                order
        );
    }

    private void handleAlreadyProcessed(
            OutboxEvent event
    ) {

        orderRepository
                .findById(
                        event.signalId()
                )
                .ifPresent(
                        order -> {

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
                        }
                );
    }
}