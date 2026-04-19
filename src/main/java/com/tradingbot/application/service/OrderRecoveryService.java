package com.tradingbot.application.service;

import com.tradingbot.domain.model.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Сервис восстановления зависших ордеров.
 * В новой архитектуре он только мониторит состояние,
 * так как OutboxProcessor автоматически гарантирует доставку PENDING событий.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderRecoveryService {

    private final OrderRepository orderRepository;
    private final ExecutionDispatcher executionDispatcher;
    private final com.tradingbot.domain.execution.OrderStateMachine stateMachine;

    private static final int BATCH_SIZE = 50;
    private static final int EXECUTING_TIMEOUT_SECONDS = 30;

    @Scheduled(fixedDelay = 30000) // Каждые 30 секунд
    public void scheduledRecovery() {
        recoverPendingOrders();
        reconcileExecutingOrders();
    }

    /**
     * Сверяет состояние ордеров в статусе EXECUTING с реальностью на бирже.
     */
    private void reconcileExecutingOrders() {
        // Находим ордера, у которых истекла аренда (fencing lease)
        Instant threshold = Instant.now().minusSeconds(EXECUTING_TIMEOUT_SECONDS);
        List<OrderEntity> stuckOrders = orderRepository.findOrdersToRecover(threshold, PageRequest.of(0, BATCH_SIZE));

        for (OrderEntity order : stuckOrders) {
            // Интересуют только те, что зависли в процессе исполнения
            if (order.getStatus() != com.tradingbot.common.enums.OrderStatus.EXECUTING) continue;

            log.info("[RECONCILIATION] Detected orphaned execution for order {}. Owner: {}, Expired: {}", 
                    order.getId(), order.getExecutionOwner(), order.getExecutionExpiresAt());
            
            try {
                // 1. Пытаемся захватить право на сверку (fencing)
                String reconciliationNodeId = "RECON-WORKER-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                if (orderRepository.claimForExecution(order.getId(), reconciliationNodeId, Instant.now().plusSeconds(60), Instant.now()) <= 0) {
                    continue; // Кто-то другой уже взял на сверку
                }

                // 2. Запрос статуса у биржи
                // Если exchangeOrderId пуст (упали до получения ответа), ищем по clientOrderId
                com.tradingbot.common.enums.OrderStatus exchangeStatus;
                if (order.getExchangeOrderId() == null || order.getExchangeOrderId().isBlank()) {
                    log.warn("[RECONCILIATION] No exchangeOrderId for {}. Checking by clientOrderId...", order.getId());
                    exchangeStatus = executionDispatcher.getExecutionEngine().getStatusByClientOrderId(
                            order.getClientOrderId(), 
                            order.getSymbol()
                    );
                } else {
                    exchangeStatus = executionDispatcher.getExecutionEngine().getStatus(
                            order.getExchangeOrderId(), 
                            order.getSymbol()
                    );
                }

                // 3. Маппинг и идемпотентное обновление через FSM
                if (exchangeStatus != com.tradingbot.common.enums.OrderStatus.EXECUTING && 
                    exchangeStatus != com.tradingbot.common.enums.OrderStatus.ERROR) {
                    
                    log.info("[RECONCILIATION] Fixing order {} status: EXECUTING -> {}", order.getId(), exchangeStatus);
                    stateMachine.transitionTo(
                            order.getId(),
                            exchangeStatus,
                            order.getExchangeOrderId(),
                            reconciliationNodeId
                    );
                } else if (exchangeStatus == com.tradingbot.common.enums.OrderStatus.ERROR) {
                    log.error("[RECONCILIATION] Exchange returned ERROR for order {}. Manual intervention might be needed.", order.getId());
                }
            } catch (Exception e) {
                log.error("[RECONCILIATION] Failed to reconcile order {}", order.getId(), e);
            }
        }
    }

    /**
     * Находит ордера, которые застряли в промежуточных статусах.
     */
    public void recoverPendingOrders() {
        Instant threshold = Instant.now().minusSeconds(EXECUTING_TIMEOUT_SECONDS);

        List<OrderEntity> orders = orderRepository.findOrdersToRecover(
                threshold,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (orders.isEmpty()) {
            return;
        }

        log.warn("[RECOVERY] Found {} orders that might be stuck", orders.size());

        for (OrderEntity order : orders) {
            log.info("[RECOVERY] Order {} needs attention (status={})",
                    order.getClientOrderId(),
                    order.getStatus());

            // В текущей архитектуре OutboxProcessor сам переотправит PENDING события.
            // Если ордер в статусе NEW/APPROVED, но события в Outbox нет —
            // здесь должна быть логика его пересоздания.
        }
    }
}
