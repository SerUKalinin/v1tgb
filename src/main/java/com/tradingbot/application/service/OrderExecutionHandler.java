package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    private final ExecutionEngine executionEngine;
    private final OrderRepository orderRepository;
    private final IdempotencyService idempotencyService;
    private final TradingSystemBootstrapper bootstrapper;

    private static final String EVENT_TYPE = "ORDER_CREATED";

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        if (bootstrapper.getState() != TradingSystemBootstrapper.SystemState.TRADING_ENABLED) {
            log.warn("[EXECUTION] Trading is not enabled (current state: {}). Skipping execution for aggregate {}", 
                    bootstrapper.getState(), event.getAggregateId());
            return;
        }

        log.info("[ИСПОЛНЕНИЕ] Начало обработки события {} для агрегата {}", event.getEventType(), event.getAggregateId());
        // 1. Идемпотентность на входе (Shift Left)
        // Проверяем и СРАЗУ фиксируем намерение обработки, чтобы исключить race condition между проверкой и IO
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[EXECUTION] Event {} already processed, skipping", event.getId());
            return;
        }

        // 2. Атомарный захват (Phase A: PENDING -> EXECUTING)
        Optional<OrderEntity> orderOpt = tryClaimOrder(event.getAggregateId());
        if (orderOpt.isEmpty()) return;

        OrderEntity order = orderOpt.get();
        ApprovedOrder approvedOrder = mapToApproved(order);

        log.info("[ИСПОЛНЕНИЕ] Вызов ExecutionEngine для ордера {}", order.getId());
        
        // 3. Исполнение (Phase B: IO outside DB transaction)
        ExecutionResult result = execute(approvedOrder);

        // 4. Фиксация (Phase C: EXECUTING -> FINAL STATUS)
        commitResult(order.getId(), result, event.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderEntity> tryClaimOrder(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId).map(order -> {
            String status = order.getStatus();

            // Если уже исполнен или в процессе - выходим (идемпотентно)
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

    @Deprecated
    @Transactional(readOnly = true)
    public Optional<OrderEntity> preFlight(UUID orderId) {
        return orderRepository.findById(orderId);
    }

    private ExecutionResult execute(ApprovedOrder approvedOrder) throws Exception {
        log.info("[EXECUTION][IO] Sending order to exchange id={}", approvedOrder.getOrderId());
        try {
            ExecutionResult result = executionEngine.execute(approvedOrder);
            if (!result.isSuccess() && "TIMEOUT".equals(result.getErrorMessage())) {
                return verifyStatus(approvedOrder);
            }
            return result;
        } catch (Exception e) {
            log.error("[EXECUTION][IO] Error for order id={}: {}", approvedOrder.getOrderId(), e.getMessage());
            return verifyStatus(approvedOrder);
        }
    }

    private ExecutionResult verifyStatus(ApprovedOrder approvedOrder) {
        try {
            log.info("[EXECUTION][VERIFY] Calling exchange to verify order id={}", approvedOrder.getOrderId());
            OrderStatusResponse binanceState = executionEngine.verifyOrder(approvedOrder.getClientOrderId());
            
            if ("FILLED".equals(binanceState.getStatus())) {
                return ExecutionResult.builder()
                        .orderId(approvedOrder.getOrderId())
                        .exchangeOrderId(binanceState.getExchangeOrderId())
                        .executedQty(binanceState.getExecutedQty())
                        .success(true)
                        .build();
            } else if ("PARTIALLY_FILLED".equals(binanceState.getStatus())) {
                return ExecutionResult.builder()
                        .orderId(approvedOrder.getOrderId())
                        .exchangeOrderId(binanceState.getExchangeOrderId())
                        .executedQty(binanceState.getExecutedQty())
                        .success(false)
                        .errorMessage("PARTIAL")
                        .build();
            } else {
                return ExecutionResult.failure(approvedOrder.getOrderId(), "Exchange status: " + binanceState.getStatus());
            }
        } catch (Exception e) {            log.error("[EXECUTION][VERIFY-FAILED] Could not verify order id={}: {}", approvedOrder.getOrderId(), e.getMessage());
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
        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
    }

    private void applyExecutionResult(OrderEntity order, ExecutionResult result) {
        UUID orderId = order.getId();
        if (result.isSuccess()) {
            order.markAsFilled(result.getExchangeOrderId(), result.getExecutedQty());
            log.info("[EXECUTION][COMMIT] Order {} marked as FILLED", orderId);
        } else if ("PARTIAL".equals(result.getErrorMessage())) {
            order.markAsPartiallyFilled(result.getExchangeOrderId(), result.getExecutedQty());
            log.info("[EXECUTION][COMMIT] Order {} marked as PARTIALLY_FILLED", orderId);
        } else {
            order.markAsRejected(result.getErrorMessage());
            log.warn("[EXECUTION][COMMIT] Order {} marked as REJECTED: {}", orderId, result.getErrorMessage());
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
