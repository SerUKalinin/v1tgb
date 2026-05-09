package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import com.tradingbot.application.event.SystemEvents;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.OptimisticLockException;


/**
 * Reconciliation Engine.
 * Ответственен за обнаружение и исправление расхождений между слоями системы
 * и внешними биржами.
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
    private final com.tradingbot.tracing.ExecutionLogger executionLogger;
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

    /**
     * 1. Сверка балансов (Account Balance Reconciliation).
     */
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

    /**
     * 2. Обнаружение зависших Outbox событий.
     */
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

    /**
     * 3. Обнаружение ордеров, которые зависли в PENDING_EXECUTION или EXECUTING.
     */
    @Scheduled(fixedDelay = 300000) // Раз в 5 минут
    public void reconcilePendingOrders() {
        Instant threshold = Instant.now().minus(STALE_THRESHOLD);
        Set<OrderStatus> reconcilableStatuses = transitionValidator.getReconcilableStatuses();

        List<Order> stuckOrders = orderRepository.findStuckOrdersInStatuses(reconcilableStatuses, threshold);

        for (Order order : stuckOrders) {
            syncOrderWithExchange(order);
        }
    }

    @Transactional
    public void reconcile(java.util.UUID orderId) {
        orderRepository.findById(orderId).ifPresent(this::syncOrderWithExchange);
    }

    @Transactional
    public void syncOrderWithExchange(Order targetOrder) {
        try {
            // 1. Атомарный захват ордера для реконсиляции (Ownership Gating)
            Optional<Order> orderOpt = orderRepository.claimForReconciliation(targetOrder.getId());
            
            if (orderOpt.isEmpty()) {
                log.debug("[RECON-SKIP] Order {} is currently executing or terminal", targetOrder.getId());
                return;
            }

            Order order = orderOpt.get();

            executionLogger.log(ExecutionLogFactory.from(
                    order,
                    ExecutionEventType.RECON_START,
                    ExecutionStateMapper.toContractState(order.getStatus()),
                    "Reconciling order " + order.getId()
            ));

            log.info("[RECON] Syncing order {} (status: {})", order.getId(), order.getStatus());
            ExecutionResult exchangeState = exchangeQueryService.getOrderStatus(order.getClientOrderId());

            boolean stateChanged = false;
            if (exchangeState.getStatus() == ExecutionResult.Status.SUCCESS) {
                order.fill(exchangeState.getExchangeOrderId(), exchangeState.getExecutedQty(), exchangeState.getExecutedPrice());
                log.info("[RECON-SUCCESS] Order {} synchronized to FILLED", order.getId());
                stateChanged = true;
            } else if (exchangeState.getStatus() == ExecutionResult.Status.REJECTED) {
                order.markAsRejected(exchangeState.getErrorMessage());
                riskEngine.release(order.getId());
                log.warn("[RECON-SUCCESS] Order {} synchronized to REJECTED", order.getId());
                stateChanged = true;
            } else {
                Instant graceThreshold = Instant.now().minus(RECONCILIATION_GRACE_PERIOD);
                if (order.getExecutionStartedAt() != null && order.getExecutionStartedAt().isBefore(graceThreshold)) {
                    log.warn("[RECON-PROPOSAL] Order {} not found after grace period. Syncing to REJECTED.", order.getId());
                    order.markAsRejected("Not found on exchange after grace period");
                    riskEngine.release(order.getId());
                    stateChanged = true;
                }
            }

            if (stateChanged) {
                // 2. Очистка owner при переходе в терминальное состояние (Compliance)
                if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
                    order.clearExecutionOwner();
                }
                
                // 3. Сохранение с проверкой Optimistic Locking
                orderRepository.save(order);
            }
        } catch (OptimisticLockException e) {
            log.warn("[RECON-CONFLICT] Stale version for order {}. Skipping this round.", targetOrder.getId());
        } catch (Exception e) {
            log.error("[RECON-ERROR] Failed to sync order {}", targetOrder.getId(), e);
        }
    }
    @Scheduled(cron = "0 0 * * * *") // Раз в час
    public void globalPositionReconciliation() {
        log.info("[RECON] Starting global position reconciliation...");
    }
}
