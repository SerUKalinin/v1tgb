package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.port.exchange.ExecutionPort;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    private final ExecutionPort executionPort;
    private final OrderRepository orderRepository;
    private final SystemStateManager stateManager;
    private final IdempotencyService idempotencyService;
    private final com.tradingbot.domain.risk.RiskEngine riskEngine;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_CREATED".equals(eventType);
    }
    @Override
    @Transactional
    public void consume(OutboxEventEntity event) throws Exception {
        // 0. Проверка состояния системы
        if (stateManager.getState() != SystemStateManager.SystemState.TRADING_ENABLED) {
            log.warn("[EXECUTION] Trading is not enabled (current state: {}). Skipping execution for aggregate {}", 
                    stateManager.getState(), event.getAggregateId());
            return;
        }

        log.info("[ИСПОЛНЕНИЕ] Начало обработки события {} для агрегата {}", event.getEventType(), event.getAggregateId());

        // 1. Идемпотентность на входе (по eventId)
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[EXECUTION] Event {} already processed, skipping", event.getId());
            return;
        }

        UUID orderId = event.getAggregateId();
        
        // 1.1 Дополнительная проверка по состоянию ордера (бизнес-идемпотентность)
        OrderEntity currentOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
        
        if (!OrderStatus.PENDING_EXECUTION.name().equals(currentOrder.getStatus())) {
            log.info("[EXECUTION] Order {} already in status {}, marking event as processed", orderId, currentOrder.getStatus());
            idempotencyService.markAsProcessed(event.getId(), "OrderExecutionHandler");
            return;
        }

        boolean committed = false;        try {
            // 2. Атомарный захват (Phase A: PENDING -> EXECUTING)
            Optional<OrderEntity> orderOpt = tryClaimOrder(orderId);
            if (orderOpt.isEmpty()) return;

            OrderEntity order = orderOpt.get();
            ApprovedOrder approvedOrder = mapToApproved(order);

            log.info("[ИСПОЛНЕНИЕ] Вызов ExecutionPort для ордера {}", order.getId());
            
            // 3. Исполнение (Phase B: IO outside DB transaction)
            ExecutionResult result = execute(approvedOrder);

            // 4. Фиксация (Phase C: EXECUTING -> FINAL STATUS)
            commitResult(order.getId(), result, event.getId());
            committed = true;
        } finally {
            if (!committed) {
                log.warn("[EXECUTION-INTERRUPTED] Flow for order {} did not reach commit. Will be retried or handled by Watchdog.", orderId);
            }
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderEntity> tryClaimOrder(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).map(order -> {
            String status = order.getStatus();

            if (!OrderStatus.PENDING_EXECUTION.name().equals(status)) {
                log.warn("[CLAIM] Order {} in status {}, cannot claim", orderId, status);
                return null;
            }

            order.setStatus(OrderStatus.EXECUTING.name());
            orderRepository.saveAndFlush(order);
            log.info("[CLAIM] Order {} successfully transitioned to EXECUTING", orderId);
            return order;
        });
    }

    private ExecutionResult execute(ApprovedOrder approvedOrder) throws Exception {
        log.info("[EXECUTION][IO] Sending order to exchange id={}", approvedOrder.getOrderId());
        ExecutionResult result = executionPort.placeOrder(approvedOrder);
        
        if (result.getStatus() == ExecutionResult.Status.TIMEOUT) {
            return verifyStatus(approvedOrder);
        }
        return result;
    }
    private ExecutionResult verifyStatus(ApprovedOrder approvedOrder) {
        try {
            log.info("[EXECUTION][VERIFY] Calling exchange to verify order id={}", approvedOrder.getOrderId());
            return executionPort.getOrderStatus(approvedOrder.getClientOrderId());
        } catch (Exception e) {
            log.error("[EXECUTION][VERIFY-FAILED] Could not verify order id={}: {}", approvedOrder.getOrderId(), e.getMessage());
            return ExecutionResult.timeout(approvedOrder.getOrderId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitResult(UUID orderId, ExecutionResult result, UUID eventId) {
        try {
            internalCommit(orderId, result, eventId);
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.warn("[COMMIT][RETRY] Optimistic lock retry for orderId={}", orderId);
            internalCommit(orderId, result, eventId);
        }
    }

    private void internalCommit(UUID orderId, ExecutionResult result, UUID eventId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Ордер не найден при коммите: " + orderId));

        String currentStatus = order.getStatus();
        boolean isFinalStatus = OrderStatus.FILLED.name().equals(currentStatus) ||
                OrderStatus.REJECTED.name().equals(currentStatus) ||
                "CANCELED".equals(currentStatus);

        if (isFinalStatus || idempotencyService.isAlreadyProcessed(eventId)) {
            log.info("[COMMIT][SKIP-RETRY] already finalized orderId={}, eventId={}", orderId, eventId);
            return;
        }

        applyExecutionResult(order, result);
        orderRepository.saveAndFlush(order);
        
        if (result.getStatus() == ExecutionResult.Status.REJECTED) {
            log.info("[COMPENSATION] Releasing risk reservation for rejected order {}", orderId);
            riskEngine.release(orderId);
            log.info("[COMPENSATION APPLIED] Risk state restored for order {}", orderId);
        }

        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
    }    private void applyExecutionResult(OrderEntity order, ExecutionResult result) {
        UUID orderId = order.getId();
        switch (result.getStatus()) {
            case SUCCESS -> {
                order.markAsFilled(result.getExchangeOrderId(), result.getExecutedQty());
                log.info("[EXECUTION][COMMIT] Order {} marked as FILLED", orderId);
            }
            case REJECTED -> {
                order.markAsRejected(result.getErrorMessage());
                log.warn("[EXECUTION][COMMIT] Order {} marked as REJECTED: {}", orderId, result.getErrorMessage());
            }
            case FAILED_IO -> {
                // Оставляем в EXECUTING для Watchdog или ручного вмешательства, 
                // либо помечаем как ошибку инфраструктуры
                log.error("[EXECUTION][COMMIT] Order {} IO FAILURE: {}", orderId, result.getErrorMessage());
            }
            case TIMEOUT -> {
                log.warn("[EXECUTION][COMMIT] Order {} TIMEOUT - status uncertain", orderId);
            }
        }
    }
    private ApprovedOrder mapToApproved(OrderEntity entity) {
        return ApprovedOrder.builder()
                .orderId(entity.getId())
                .clientOrderId(entity.getClientOrderId())
                .symbol(entity.getSymbol())
                .side(entity.getSide())
                .type(entity.getType())
                .quantity(entity.getQuantity())
                .price(entity.getPrice())
                .stopLoss(entity.getStopLoss())
                .takeProfit(entity.getTakeProfit())
                .strategyId(entity.getStrategyId())
                .approvedAt(entity.getCreatedAt())
                .build();
    }
}
