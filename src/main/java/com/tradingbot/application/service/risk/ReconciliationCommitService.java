package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.execution.OrderExecutionCommitService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Application boundary для recovery/reconciliation commit.
 *
 * Exchange I/O здесь отсутствует.
 *
 * Единственный transactional owner recovery commit:
 * OrderExecutionCommitService.commitRecoveredExecution().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationCommitService {

    private final OrderExecutionCommitService orderExecutionCommitService;

    /**
     * Передаёт authoritative exchange result
     * в единый transactional recovery boundary.
     *
     * Аргументы намеренно фиксированы:
     *
     * 1. context
     * 2. order
     * 3. result
     * 4. settlementKey
     * 5. incrementalNotional
     * 6. lockKey
     */
    public void commit(
            ExecutionContext context,
            Order order,
            ExecutionResult result,
            String settlementKey,
            BigDecimal incrementalNotional,
            String lockKey
    ) {

        if (context == null) {

            throw new IllegalArgumentException(
                    "Reconciliation commit requires ExecutionContext"
            );
        }

        if (order == null) {

            throw new IllegalArgumentException(
                    "Reconciliation commit requires Order"
            );
        }

        if (result == null) {

            throw new IllegalArgumentException(
                    "Reconciliation commit requires ExecutionResult"
            );
        }

        if (context.attempt() == null
                || context.attempt().executionId() == null) {

            throw new IllegalStateException(
                    "Reconciliation commit requires canonical executionId"
            );
        }

        if (order.getExecutionId() == null) {

            throw new IllegalStateException(
                    "Order must already have executionId " +
                            "before reconciliation commit"
            );
        }

        if (!order.getExecutionId().equals(
                context.attempt().executionId()
        )) {

            throw new IllegalStateException(
                    "EXECUTION IDENTITY MISMATCH during reconciliation: "
                            + "orderExecutionId="
                            + order.getExecutionId()
                            + ", contextExecutionId="
                            + context.attempt().executionId()
            );
        }

        if (incrementalNotional == null) {

            throw new IllegalArgumentException(
                    "Reconciliation incrementalNotional cannot be null"
            );
        }

        if (incrementalNotional.signum() < 0) {

            throw new IllegalArgumentException(
                    "Reconciliation incrementalNotional cannot be negative"
            );
        }

        if (lockKey == null
                || lockKey.isBlank()) {

            throw new IllegalArgumentException(
                    "Reconciliation execution lock key cannot be blank"
            );
        }

        if (isTerminal(order.getStatus())) {

            log.info(
                    "[RECON-COMMIT-SKIP] " +
                            "Order already terminal. " +
                            "orderId={}, status={}, executionId={}",
                    order.getId(),
                    order.getStatus(),
                    context.attempt().executionId()
            );

            return;
        }

        log.info(
                "[RECON-COMMIT] " +
                        "Delegating recovery commit. " +
                        "orderId={}, executionId={}, status={}, " +
                        "settlementKey={}, incrementalNotional={}",
                order.getId(),
                context.attempt().executionId(),
                result.getStatus(),
                settlementKey,
                incrementalNotional
        );

        orderExecutionCommitService.commitRecoveredExecution(
                context,
                order,
                result,
                settlementKey,
                incrementalNotional,
                lockKey
        );
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