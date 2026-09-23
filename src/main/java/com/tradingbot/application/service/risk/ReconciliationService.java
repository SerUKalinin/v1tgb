package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.event.SystemEvents;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxRecoveryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.binance.BinanceStatusMapper;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Сервис реконсиляции состояния торговой системы.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationService {

    private final OrderRepositoryPort orderRepository;
    private final OutboxRecoveryPort outboxRecoveryPort;
    private final ExchangeOrderQueryService exchangeQueryService;
    private final OrderCompensationService orderCompensationService;
    private final RiskEngine riskEngine;
    private final AdminNotificationService notifications;
    private final PositionRebuildService positionRebuildService;
    private final SystemStateManager stateManager;
    private final TransitionValidator transitionValidator;
    private final ExecutionLogger executionLogger;

    private static final Duration STALE_THRESHOLD =
            Duration.ofMinutes(2);

    private static final Duration DRIFT_DETECTION_WINDOW =
            Duration.ofSeconds(45);

    private static final BigDecimal DRIFT_THRESHOLD =
            new BigDecimal("0.01");

    private static final Duration RECONCILIATION_GRACE_PERIOD =
            Duration.ofSeconds(30);

    private Instant lastReconcileTimestamp =
            Instant.now();

    @EventListener
    public void onColdStart(
            SystemEvents.ColdStartDetectedEvent event
    ) {
        log.info(
                "[RECON] Handling Cold Start event. " +
                        "Forcing reconciliation..."
        );

        reconcileAll(true);
    }

    @EventListener
    public void onStandardRecon(
            SystemEvents.StandardReconciliationRequestedEvent event
    ) {
        log.info(
                "[RECON] Handling Standard Reconciliation event."
        );

        reconcileAll(false);
    }

    @Scheduled(fixedDelay = 3600000)
    public void reconcileAll() {
        reconcileAll(false);
    }

    public void reconcileAll(
            boolean force
    ) {
        reconcileOutbox();
        reconcilePendingOrders();
        reconcileBalances(force);
    }

    public void reconcileBalances(
            boolean force
    ) {

        try {

            BigDecimal exchangeBalance =
                    exchangeQueryService.getAvailableBalance(
                            "USDT"
                    );

            BigDecimal internalBalance =
                    riskEngine
                            .getState()
                            .availableBalance();

            boolean isColdStart =
                    stateManager.isColdStart()
                            || stateManager.getState()
                            == SystemStateManager.SystemState.COLD_START_RECONCILIATION;

            if (isColdStart) {

                log.info(
                        "[BOOTSTRAP_SYNC] Cold start reconciliation active. " +
                                "Force syncing internal balance: {} -> {}",
                        internalBalance,
                        exchangeBalance
                );

                riskEngine.syncBalance(
                        exchangeBalance
                );

                positionRebuildService.rebuildAllPositions();

                lastReconcileTimestamp =
                        Instant.now();

                return;
            }

            if (internalBalance.signum() == 0
                    && exchangeBalance.signum() == 0) {

                return;
            }

            BigDecimal diff =
                    exchangeBalance
                            .subtract(internalBalance)
                            .abs();

            if (diff.signum() == 0) {
                return;
            }

            BigDecimal driftPercent =
                    internalBalance.signum() != 0
                            ? diff.divide(
                            internalBalance,
                            4,
                            RoundingMode.HALF_UP
                    )
                            : BigDecimal.ONE;

            Instant now =
                    Instant.now();

            if (!force
                    && now.isBefore(
                    lastReconcileTimestamp
                            .plus(
                                    DRIFT_DETECTION_WINDOW
                            )
            )) {
                return;
            }

            if (driftPercent.compareTo(
                    DRIFT_THRESHOLD
            ) > 0) {

                log.error(
                        "[RECON-CRITICAL] CRITICAL balance drift detected: Drift={}%",
                        driftPercent.multiply(
                                new BigDecimal("100")
                        )
                );

                riskEngine.syncBalance(
                        exchangeBalance
                );

                positionRebuildService.rebuildAllPositions();

                if (stateManager.isReady()
                        && !isColdStart) {

                    notifications.sendCritical(
                            "Trading HALTED: Balance drift exceeds 1%."
                    );

                    riskEngine.emergencyStop(
                            "Critical balance drift: "
                                    + driftPercent
                    );
                }

            } else {

                log.info(
                        "[RECON] Minor drift detected. " +
                                "Auto-repairing state."
                );

                riskEngine.syncBalance(
                        exchangeBalance
                );

                positionRebuildService.rebuildAllPositions();
            }

            lastReconcileTimestamp =
                    now;

        } catch (Exception e) {

            log.error(
                    "[RECON] Failed to reconcile balances",
                    e
            );
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void reconcileOutbox() {

        Instant threshold =
                Instant.now()
                        .minusSeconds(30);

        int recovered =
                outboxRecoveryPort
                        .resetStaleProcessingEvents(
                                threshold
                        );

        if (recovered > 0) {

            log.warn(
                    "[RECON] Found {} stuck outbox events. " +
                            "Resetting to FAILED.",
                    recovered
            );
        }
    }

    @Scheduled(fixedDelay = 300000)
    public void reconcilePendingOrders() {

        Instant threshold =
                Instant.now()
                        .minus(
                                STALE_THRESHOLD
                        );

        Set<OrderStatus> reconcilableStatuses =
                transitionValidator
                        .getReconcilableStatuses();

        List<Order> stuckOrders =
                orderRepository
                        .findStuckOrdersInStatuses(
                                reconcilableStatuses,
                                threshold
                        );

        for (Order order : stuckOrders) {

            ExecutionContext context =
                    ExecutionContext.of(order);

            syncOrderWithExchange(
                    order,
                    context
            );
        }
    }

    public void reconcile(
            ExecutionContext context
    ) {

        orderRepository.findById(
                UUID.fromString(
                        context.business().orderId()
                )
        ).ifPresent(
                order ->
                        syncOrderWithExchange(
                                order,
                                context
                        )
        );
    }

    /**
     * Синхронизация состояния ордера
     * с authoritative exchange state.
     *
     * <p>
     * Exchange I/O выполняется вне DB transaction.
     */
    public void syncOrderWithExchange(
            Order targetOrder,
            ExecutionContext context
    ) {

        try {

            Optional<Order> orderOpt =
                    orderRepository.claimForReconciliation(
                            targetOrder.getId()
                    );

            if (orderOpt.isEmpty()) {

                log.debug(
                        "[RECON-SKIP] Order {} is currently owned by " +
                                "another execution/reconciliation worker " +
                                "or is terminal.",
                        targetOrder.getId()
                );

                return;
            }

            Order order =
                    orderOpt.get();

            executionLogger.log(
                    ExecutionLogFactory.from(
                            order,
                            context,
                            ExecutionEventType.RECON_START,
                            ExecutionStateMapper.toContractState(
                                    order.getStatus()
                            ),
                            "Reconciling order " + order.getId()
                    )
            );

            log.info(
                    "[RECON] Syncing order {} (status: {}).",
                    order.getId(),
                    order.getStatus()
            );

            /*
             * Capture the previous cumulative execution BEFORE
             * mutating the Order.
             */
            BigDecimal previousExecutedQuantity =
                    valueOrZero(
                            order.getExecutedQuantity()
                    );

            BigDecimal previousAveragePrice =
                    valueOrZero(
                            order.getAveragePrice()
                    );

            /*
             * Exchange I/O intentionally remains outside DB transaction.
             */
            ExecutionResult exchangeState =
                    exchangeQueryService.getOrderStatus(
                            order.getSymbol(),
                            order.getClientOrderId()
                    );

            BinanceStatusMapper.Action action =
                    BinanceStatusMapper.mapToReconciliationAction(
                            exchangeState.getStatus()
                    );

            boolean stateChanged =
                    switch (action) {

                        case FORCE_FILL, FILL -> {

                            BigDecimal deltaNotional =
                                    calculateIncrementalNotional(
                                            previousExecutedQuantity,
                                            previousAveragePrice,
                                            exchangeState
                                    );

                            order.forceFill(
                                    context,
                                    exchangeState.getExchangeOrderId(),
                                    exchangeState.getExecutedQty(),
                                    exchangeState.getExecutedPrice()
                            );

                            settleIncrementalExecution(
                                    order,
                                    exchangeState,
                                    deltaNotional,
                                    "Recovery full fill"
                            );

                            yield true;
                        }

                        case PARTIALLY_FILL -> {

                            BigDecimal deltaNotional =
                                    calculateIncrementalNotional(
                                            previousExecutedQuantity,
                                            previousAveragePrice,
                                            exchangeState
                                    );

                            order.applyPartialFill(
                                    context,
                                    exchangeState.getExecutedQty(),
                                    exchangeState.getExecutedPrice()
                            );

                            settleIncrementalExecution(
                                    order,
                                    exchangeState,
                                    deltaNotional,
                                    "Recovery partial fill"
                            );

                            yield true;
                        }

                        case MARK_ACCEPTED -> {

                            order.markAccepted(
                                    context,
                                    exchangeState.getExchangeOrderId()
                            );

                            yield true;
                        }

                        case REJECT -> {

                            order.markAsRejected(
                                    context,
                                    exchangeState.getErrorMessage()
                            );

                            orderCompensationService.releasePartial(
                                    order,
                                    order.getExecutedQuantity()
                            );

                            yield true;
                        }

                        case CANCEL -> {

                            order.markCancelled(
                                    context
                            );

                            orderCompensationService.releasePartial(
                                    order,
                                    order.getExecutedQuantity()
                            );

                            yield true;
                        }

                        case MARK_UNKNOWN -> {

                            order.markAsUnknown(
                                    context
                            );

                            yield true;
                        }

                        case NOOP ->
                                false;
                    };

            /*
             * Order persistence remains isolated from exchange I/O.
             */
            if (stateChanged) {

                orderRepository.save(
                        order
                );
            }

        } catch (OptimisticLockException e) {

            log.warn(
                    "[RECON-CONFLICT] Stale version for order {}. Skipping.",
                    targetOrder.getId()
            );

        } catch (Exception e) {

            log.error(
                    "[RECON-ERROR] Failed to sync order {}",
                    targetOrder.getId(),
                    e
            );
        }
    }

    /**
     * Calculates the newly executed notional represented by the
     * exchange cumulative execution state.
     *
     * <p>
     * Example:
     *
     * <pre>
     * previous = 0.3 @ 100  -> 30
     * current  = 0.6 @ 100  -> 60
     * delta                  -> 30
     * </pre>
     *
     * <p>
     * This is intentionally calculated from cumulative notionals,
     * not simply deltaQty * currentAveragePrice.
     */
    private BigDecimal calculateIncrementalNotional(
            BigDecimal previousQuantity,
            BigDecimal previousAveragePrice,
            ExecutionResult exchangeState
    ) {

        BigDecimal currentQuantity =
                exchangeState.getExecutedQty();

        BigDecimal currentAveragePrice =
                exchangeState.getExecutedPrice();

        if (currentQuantity == null
                || currentQuantity.signum() <= 0) {

            throw new IllegalStateException(
                    "Recovery execution quantity must be positive. orderId="
                            + exchangeState.getOrderId()
            );
        }

        if (currentAveragePrice == null
                || currentAveragePrice.signum() <= 0) {

            throw new IllegalStateException(
                    "Recovery execution price must be positive. orderId="
                            + exchangeState.getOrderId()
            );
        }

        BigDecimal previousNotional =
                BigDecimal.ZERO;

        if (previousQuantity.signum() > 0
                && previousAveragePrice.signum() > 0) {

            previousNotional =
                    previousQuantity.multiply(
                            previousAveragePrice
                    );
        }

        BigDecimal currentNotional =
                currentQuantity.multiply(
                        currentAveragePrice
                );

        BigDecimal deltaNotional =
                currentNotional.subtract(
                        previousNotional
                );

        if (deltaNotional.signum() < 0) {

            throw new IllegalStateException(
                    "Recovery executed notional cannot decrease. " +
                            "previous=" + previousNotional +
                            ", current=" + currentNotional +
                            ", orderId=" + exchangeState.getOrderId()
            );
        }

        return deltaNotional;
    }

    /**
     * Applies the newly settled execution amount exactly once.
     */
    private void settleIncrementalExecution(
            Order order,
            ExecutionResult exchangeState,
            BigDecimal deltaNotional,
            String reason
    ) {

        if (deltaNotional.signum() == 0) {

            log.info(
                    "[RECON-RISK-SKIP] No incremental settlement for order {}. " +
                            "exchangeQty={}, exchangePrice={}",
                    order.getId(),
                    exchangeState.getExecutedQty(),
                    exchangeState.getExecutedPrice()
            );

            return;
        }

        String settlementKey =
                buildSettlementKey(
                        exchangeState
                );

        orderCompensationService.settleIncrementalExecution(
                order,
                deltaNotional,
                settlementKey,
                reason
        );
    }

    /**
     * Stable identity for a cumulative exchange checkpoint.
     */
    private String buildSettlementKey(
            ExecutionResult exchangeState
    ) {

        return exchangeState.getStatus().name()
                + ":"
                + normalizeDecimal(
                exchangeState.getExecutedQty()
        )
                + "@"
                + normalizeDecimal(
                exchangeState.getExecutedPrice()
        );
    }

    private String normalizeDecimal(
            BigDecimal value
    ) {

        return value
                .stripTrailingZeros()
                .toPlainString();
    }

    private BigDecimal valueOrZero(
            BigDecimal value
    ) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }
}