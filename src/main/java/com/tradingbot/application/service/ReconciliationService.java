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
    
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);
    private static final BigDecimal DRIFT_THRESHOLD = new BigDecimal("0.01"); // 1%

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("[RECON] Startup reconciliation triggered.");
        reconcileAll();
    }

    @Scheduled(fixedDelay = 3600000) // Hourly
    public void reconcileAll() {
        reconcileOutbox();
        reconcilePendingOrders();
        reconcileBalances();
    }

    /**
     * 1. Сверка балансов (Account Balance Reconciliation).
     */
    public void reconcileBalances() {
        try {
            BigDecimal exchangeBalance = exchangeQueryService.getAvailableBalance("USDT");
            BigDecimal internalBalance = riskEngine.getState().availableBalance();

            if (internalBalance.signum() == 0) return;

            BigDecimal diff = exchangeBalance.subtract(internalBalance).abs();
            BigDecimal driftPercent = diff.divide(internalBalance, 4, RoundingMode.HALF_UP);

            if (driftPercent.compareTo(DRIFT_THRESHOLD) > 0) {
                log.error("[RECON-FATAL] Critical balance drift: Exchange={}, Internal={}, Drift={}%", 
                    exchangeBalance, internalBalance, driftPercent.multiply(new BigDecimal("100")));
                
                riskEngine.emergencyStop("Critical balance drift detected: " + driftPercent);
                notifications.sendCritical("Trading HALTED: Balance drift exceeds 1%");
            } else if (diff.signum() != 0) {
                log.warn("[RECON] Minor drift detected. Auto-repairing RiskState. Diff={}", diff);
                riskEngine.syncBalance(exchangeBalance);
            }
        } catch (Exception e) {
            log.error("[RECON] Failed to reconcile balances", e);
        }
    }

    /**
     * 1. Обнаружение зависших Outbox событий.
     * PROCESSING > 30 sec -> reset to FAILED
     * FAILED events re-enter claim cycle
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void reconcileOutbox() {
        Instant threshold = Instant.now().minusSeconds(30);
        List<OutboxEventEntity> stuckEvents = outboxRepository.findStaleProcessingEvents(threshold);
        
        if (!stuckEvents.isEmpty()) {            log.warn("[RECON] Found {} stuck outbox events in PROCESSING. Resetting to FAILED for retry.", stuckEvents.size());
            stuckEvents.forEach(event -> {
                event.setStatus(OutboxStatus.FAILED);
                event.setUpdatedAt(Instant.now());
            });
            outboxRepository.saveAll(stuckEvents);
        }
    }


    /**
     * 2. Обнаружение ордеров, которые зависли в PENDING_EXECUTION.
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

    /**
     * 3. Синхронизация конкретного ордера с биржей.
     */
    @Transactional
    public void syncOrderWithExchange(OrderEntity order) {
        try {
            boolean existsOnExchange = exchangeQueryService.isOrderAlreadyExecuted(order.getClientOrderId());

            if (existsOnExchange) {
                log.info("[RECON] Order {} found on exchange. Marking as FILLED (manual check required for partials).", order.getId());
                order.setStatus(OrderStatus.FILLED.name());
                // В реальной реализации здесь нужно получить детали исполнения (qty, price)
            } else {
                log.warn("[RECON] Order {} NOT FOUND on exchange. Releasing capital and rejecting.", order.getId());
                
                BigDecimal releaseAmount = order.getPrice() != null 
                        ? order.getQuantity().multiply(order.getPrice()) 
                        : BigDecimal.ZERO;
                
                riskEngine.release(order.getId(), releaseAmount, "Reconciliation: Order not found on exchange");
                order.setStatus(OrderStatus.REJECTED.name());
            }
            
            orderRepository.save(order);
            
        } catch (Exception e) {
            log.error("[RECON] Failed to sync order {} with exchange", order.getId(), e);
        }
    }
    /**
     * 4. Глобальная сверка балансов и позиций (Placeholder).
     */
    @Scheduled(cron = "0 0 * * * *") // Раз в час
    public void globalPositionReconciliation() {
        log.info("[RECON] Starting global position reconciliation...");
        // TODO: Сверка PositionEntity с Account Information от биржи
    }
}
