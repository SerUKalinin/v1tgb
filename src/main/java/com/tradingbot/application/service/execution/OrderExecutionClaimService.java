package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
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
 * <p>Гарантирует, что execution claim и переход Order
 * PENDING_EXECUTION -> EXECUTING выполняются в одной транзакции.</p>
 *
 * <p>Сервис вынесен в отдельный Spring bean, чтобы
 * {@link Transactional} с REQUIRES_NEW реально применялся
 * через Spring proxy.</p>
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
     * Атомарно захватывает execution и переводит Order в EXECUTING.
     *
     * <p>При исключении вся транзакция откатывается целиком:
     * execution claim не останется в БД отдельно от состояния Order.</p>
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public Optional<Order> claim(
            OutboxEventEntity event,
            ExecutionContext context,
            OrderCreatedEvent payload
    ) {
        if (stateManager != null && !stateManager.isReady()) {
            throw new IllegalStateException("System not ready for execution");
        }

        UUID executionId = context.attempt().executionId();
        UUID orderId = payload.orderId();
        UUID signalId = payload.signalId();
        UUID payloadExecutionId = payload.executionId();

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
         * ВАЖНО:
         *
         * Сначала блокируем сам Order через SELECT ... FOR UPDATE
         * и атомарно переводим его PENDING_EXECUTION -> EXECUTING.
         *
         * Это является главным барьером конкурентного execution.
         *
         * Нельзя сначала делать existsByExecutionId(), потому что
         * это TOCTOU race:
         *
         * T1: exists -> false
         * T2: exists -> false
         * T1: INSERT claim
         * T2: INSERT claim -> 23505
         *
         * После блокировки Order второй поток дождётся первого,
         * затем увидит EXECUTING и завершит claim без INSERT.
         */
        Optional<Order> orderOpt =
                orderRepository.claimForExecution(orderId, context);

        if (orderOpt.isEmpty()) {
            handleAlreadyProcessed(event);
            return Optional.empty();
        }

        /*
         * Order уже успешно захвачен и находится в EXECUTING.
         *
         * Execution claim создаётся в той же REQUIRES_NEW транзакции.
         * Если INSERT завершится ошибкой, вся транзакция откатится,
         * включая переход Order в EXECUTING.
         */
        executionClaimPort.claimExecution(executionId, signalId);

        return orderOpt;
    }

    private void handleAlreadyProcessed(OutboxEventEntity event) {
        orderRepository.findById(event.getSignalId()).ifPresent(order -> {
            if (transitionValidator.isProcessed(order.getStatus())) {
                log.debug(
                        "[EXECUTION-ALREADY-PROCESSED] Order {} already in processed state {}",
                        order.getId(),
                        order.getStatus()
                );
            }
        });
    }
}