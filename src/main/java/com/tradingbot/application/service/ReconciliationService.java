package com.tradingbot.application.service;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.risk.RiskEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.math.RoundingMode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.tradingbot.application.event.SystemEvents;
import org.springframework.context.event.EventListener;

/**
 * Reconciliation Engine.
 * Ответственен за обнаружение и исправление расхождений между слоями системы
 * и внешними биржами.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconciliationService {
    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxRepository;
    private final ExchangeOrderQueryService exchangeQueryService;
    private final RiskEngine riskEngine;
    private final AdminNotificationService notifications;
    private final PositionRebuildService positionRebuildService;
    private final SystemStateManager stateManager;

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);
    private static final Duration DRIFT_DETECTION_WINDOW = Duration.ofSeconds(45);
    private static final BigDecimal DRIFT_THRESHOLD = new BigDecimal("0.01"); // 1%
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

            // Условие холодного старта: система в режиме восстановления ИЛИ баланс в БД пуст при наличии средств на бирже
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
                log.debug("[RECON] Within drift window, skipping repair to allow eventual consistency.");
                return;
            }

            if (driftPercent.compareTo(DRIFT_THRESHOLD) > 0) {
                log.error("[RECON-CRITICAL] CRITICAL balance drift detected: Exchange={}, Internal={}, Drift={}%",
                        exchangeBalance, internalBalance, driftPercent.multiply(new BigDecimal("100")));

                // 1. Сначала синхронизируем состояние, чтобы RiskEngine имел актуальные данные
                log.info("[RECON] Performing emergency balance synchronization...");
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();

                // 2. Затем останавливаем торговлю, если мы в рабочем режиме
                if (stateManager.isReady() && !isColdStart) {
                    notifications.sendCritical("Trading HALTED: Balance drift exceeds 1%. State synchronized.");
                    riskEngine.emergencyStop("Critical balance drift: " + driftPercent);
                } else {
                    log.warn("[RECON] Critical drift detected during bootstrap/cold-start. State synchronized, skipping emergency stop.");
                }
            } else {
                log.info("[RECON] Minor drift detected. Auto-repairing RiskState and Rebuilding Positions. Diff={}", diff);
                riskEngine.syncBalance(exchangeBalance);
                positionRebuildService.rebuildAllPositions();
            }            lastReconcileTimestamp = now;
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
            log.warn("[RECON] Found {} stuck outbox events in PROCESSING. Resetting to FAILED for retry.", stuckEvents.size());
            stuckEvents.forEach(event -> {
                event.setStatus(OutboxStatus.FAILED);
                event.setUpdatedAt(Instant.now());
            });
            outboxRepository.saveAll(stuckEvents);
        }
    }
    /**
     * 3. Обнаружение ордеров, которые зависли в PENDING_EXECUTION.
     */
    @Scheduled(fixedDelay = 300000) // Раз в 5 минут
    public void reconcilePendingOrders() {
        Instant threshold = Instant.now().minus(ReconciliationService.STALE_THRESHOLD);

        List<OrderEntity> pendingOrders = orderRepository.findAll().stream()
                .filter(o -> OrderStatus.PENDING_EXECUTION.name().equals(o.getStatus()))
                .filter(o -> o.getCreatedAt().isBefore(threshold))
                .toList();
        for (OrderEntity order : pendingOrders) {
            log.error("[RECON] Order {} is stuck in PENDING_EXECUTION. Syncing...", order.getId());
            syncOrderWithExchange(order);
        }
    }
    @Transactional
    public void reconcile(java.util.UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        syncOrderWithExchange(order);
    }
    @Transactional
    public void syncOrderWithExchange(OrderEntity order) {
        try {
            boolean existsOnExchange = exchangeQueryService.isOrderAlreadyExecuted(order.getClientOrderId());
            if (existsOnExchange) {
                log.info("[RECON] Order {} found on exchange. Marking as FILLED.", order.getId());
                order.markAsFilled("RECON-SYNC", order.getQuantity());
            } else {
                log.warn("[RECON] Order {} NOT FOUND on exchange. Releasing capital and rejecting.", order.getId());

                BigDecimal releaseAmount = order.getPrice() != null
                        ? order.getQuantity().multiply(order.getPrice())
                        : BigDecimal.ZERO;

                riskEngine.release(order.getId(), releaseAmount, "Reconciliation: Order not found on exchange");
                order.markAsRejected("Not found on exchange during reconciliation");
            }
            orderRepository.save(order);

        } catch (Exception e) {
            log.error("[RECON] Failed to sync order {} with exchange", order.getId(), e);
        }
    }
    @Scheduled(cron = "0 0 * * * *") // Раз в час
    public void globalPositionReconciliation() {
        log.info("[RECON] Starting global position reconciliation...");
    }
}