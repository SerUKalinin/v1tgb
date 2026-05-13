package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.event.SystemEvents;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.tracing.*;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <h1>ReconciliationService</h1>
 *
 * <p>Ответственен за обнаружение и исправление расхождений между слоями системы
 * и внешними биржами. Реализует recovery semantics для зависших ордеров.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationService {
    private final OrderRepositoryPort orderRepository;
    private final OutboxEventRepository outboxRepository;
    private final ExchangeOrderQueryService exchangeQueryService;
    private final RiskEngine riskEngine;
    private final AdminNotificationService notifications;
    private final PositionRebuildService positionRebuildService;
    private final SystemStateManager stateManager;
    private final TransitionValidator transitionValidator;
    private final ExecutionLogger executionLogger;

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);
    private static final Duration DRIFT_DETECTION_WINDOW = Duration.ofSeconds(45);
    private static final BigDecimal DRIFT_THRESHOLD = new BigDecimal("0.01"); // 1%
    private static final Duration RECONCILIATION_GRACE_PERIOD = Duration.ofSeconds(30);

    private Instant lastReconcileTimestamp = Instant.now();

    @EventListener
    public void onColdStart(SystemEvents.ColdStartDetectedEvent event) {
        log.info("[RECON] Handling Cold Start event. Forcing reconciliation...");
        reconcileAll(true);
    }

    @EventListener
    public void onStandardRecon(SystemEvents.StandardReconciliationRequestedEvent event) {
        log.info("[RECON] Handling Standard Reconciliation event.");
        reconcileAll(false);
    }

    @Scheduled(fixedDelay = 3600000) // Hourly
    public void reconcileAll() {
        reconcileAll(false);
    }

    public void reconcileAll(boolean force) {
        reconcileOutbox();
        reconcilePendingOrders();
        reconcileBalances(force);
    }

    public void reconcileBalances(boolean force) {
        try {
            BigDecimal exchangeBalance = exchangeQueryService.getAvailableBalance("USDT");
            BigDecimal internalBalance = riskEngine.getState().availableBalance();

            boolean isColdStart = stateManager.isColdStart() ||
                    (stateManager.getState() == SystemStateManager.SystemState.COLD_START_RECONCILIATION);

            if (isColdStart) {
                log.info("[BOOTSTRAP_SYNC] Cold start reconciliation active. Force syncing internal balance: {} -> {}",
                        internalBalance, exchangeBalance);
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();
                lastReconcileTimestamp = Instant.now();
                return;
            }

            if (internalBalance.signum() == 0 && exchangeBalance.signum() == 0) return;
            BigDecimal diff = exchangeBalance.subtract(internalBalance).abs();
            if (diff.signum() == 0) return;

            BigDecimal driftPercent = internalBalance.signum() != 0
                    ? diff.divide(internalBalance, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ONE;

            Instant now = Instant.now();
            if (!force && now.isBefore(lastReconcileTimestamp.plus(DRIFT_DETECTION_WINDOW))) {
                return;
            }

            if (driftPercent.compareTo(DRIFT_THRESHOLD) > 0) {
                log.error("[RECON-CRITICAL] CRITICAL balance drift detected: Drift={}%", driftPercent.multiply(new BigDecimal("100")));
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();

                if (stateManager.isReady() && !isColdStart) {
                    notifications.sendCritical("Trading HALTED: Balance drift exceeds 1%.");
                    riskEngine.emergencyStop("Critical balance drift: " + driftPercent);
                }
            } else {
                log.info("[RECON] Minor drift detected. Auto-repairing state.");
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();
            }
            lastReconcileTimestamp = now;
        } catch (Exception e) {
            log.error("[RECON] Failed to reconcile balances", e);
        }
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void reconcileOutbox() {
        Instant threshold = Instant.now().minusSeconds(30);
        List<OutboxEventEntity> stuckEvents = outboxRepository.findStaleProcessingEvents(threshold);

        if (!stuckEvents.isEmpty()) {
            log.warn("[RECON] Found {} stuck outbox events. Resetting to FAILED.", stuckEvents.size());
            stuckEvents.forEach(event -> {
                event.setStatus(OutboxStatus.FAILED);
                event.setUpdatedAt(Instant.now());
            });
            outboxRepository.saveAll(stuckEvents);
        }
    }

    @Scheduled(fixedDelay = 300000) // Раз в пять минут
    public void reconcilePendingOrders() {
        Instant threshold = Instant.now().minus(STALE_THRESHOLD);
        Set<OrderStatus> reconcilableStatuses = transitionValidator.getReconcilableStatuses();

        List<Order> stuckOrders = orderRepository.findStuckOrdersInStatuses(reconcilableStatuses, threshold);

        for (Order order : stuckOrders) {
            ExecutionContext context = ExecutionContext.of(order);
            syncOrderWithExchange(order, context);
        }
    }

    @Transactional
    public void reconcile(ExecutionContext context) {
        orderRepository.findById(UUID.fromString(context.business().orderId()))
                .ifPresent(order -> syncOrderWithExchange(order, context));
    }

    @Transactional
    public void syncOrderWithExchange(Order targetOrder, ExecutionContext context) {
        try {
            // 1. Атомарный захват ордера для реконсиляции
            Optional<Order> orderOpt = orderRepository.claimForReconciliation(targetOrder.getId());

            if (orderOpt.isEmpty()) {
                log.debug("[RECON-SKIP] Order {} is currently executing or terminal.", targetOrder.getId());
                return;
            }

            Order order = orderOpt.get();

            // Recovery Semantics: UNKNOWN -> RECOVERING
            if (order.getStatus() == OrderStatus.UNKNOWN) {
                order.markRecovering(context);
                orderRepository.save(order);
            }

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    context,
                    ExecutionEventType.RECON_START,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Reconciling order " + order.getId()
            ));

            log.info("[RECON] Syncing order {} (status: {}).", order.getId(), order.getStatus());
            ExecutionResult exchangeState = exchangeQueryService.getOrderStatus(order.getClientOrderId());

            boolean stateChanged = false;
            if (exchangeState.getStatus() == ExecutionResult.Status.SUCCESS) {
                // RECOVERING -> FILLED
                order.fill(context, exchangeState.getExchangeOrderId(), exchangeState.getExecutedQty(), exchangeState.getExecutedPrice());
                log.info("[RECON-SUCCESS] Order {} synchronized to FILLED.", order.getId());
                stateChanged = true;
            } else if (exchangeState.getStatus() == ExecutionResult.Status.REJECTED) {
                // RECOVERING -> REJECTED
                order.markAsRejected(context, exchangeState.getErrorMessage());
                riskEngine.release(context, order.getQuantity(), "Reconciliation rejection");
                log.warn("[RECON-SUCCESS] Order {} synchronized to REJECTED.", order.getId());
                stateChanged = true;
            } else if (exchangeState.getStatus() == ExecutionResult.Status.CANCELED) {
                // RECOVERING -> CANCELED
                order.markCancelled(context);
                riskEngine.release(context, order.getQuantity(), "Reconciliation cancellation");
                log.warn("[RECON-SUCCESS] Order {} synchronized to CANCELED.", order.getId());
                stateChanged = true;
            } else {
                // Обработка stuck PENDING_EXECUTION или RECOVERING без ответа от биржи
                Instant graceThreshold = Instant.now().minus(RECONCILIATION_GRACE_PERIOD);
                Instant startTime = order.getExecutionStartedAt() != null ? order.getExecutionStartedAt() : order.getCreatedAt();

                if (startTime != null && startTime.isBefore(graceThreshold)) {
                    log.warn("[RECON-PROPOSAL] Order {} not found after grace period. Syncing to REJECTED.", order.getId());
                    order.markAsRejected(context, "Not found on exchange after grace period");
                    riskEngine.release(context, order.getQuantity(), "Reconciliation not found");
                    stateChanged = true;
                }
            }

            if (stateChanged) {
                if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
                    order.clearExecutionOwner(context);
                }
                orderRepository.save(order);
            }
        } catch (OptimisticLockException e) {
            log.warn("[RECON-CONFLICT] Stale version for order {}. Skipping.", targetOrder.getId());
        } catch (Exception e) {
            log.error("[RECON-ERROR] Failed to sync order {}", targetOrder.getId(), e);
        }
    }
}
