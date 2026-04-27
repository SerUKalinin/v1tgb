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

/**
 * Обработчик исполнения ордеров.
 * Реализует Execution Protocol v2.0: разделение на фазы preFlight, execute и commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    private final ExecutionEngine executionEngine;
    private final OrderRepository orderRepository;
    private final IdempotencyService idempotencyService;

    private static final String EVENT_TYPE = "ORDER_CREATED";

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        log.info("[ИСПОЛНЕНИЕ] Начало обработки события {} для агрегата {}", event.getEventType(), event.getAggregateId());

        // 1. Фаза preFlight: только чтение
        Optional<OrderEntity> orderOpt = preFlight(event.getAggregateId());
        if (orderOpt.isEmpty()) return;

        OrderEntity order = orderOpt.get();
        ApprovedOrder approvedOrder = mapToApproved(order);

        // 2. Фаза execute: сетевой вызов вне транзакции
        log.info("[ИСПОЛНЕНИЕ] Вызов ExecutionEngine для ордера {}", order.getId());
        ExecutionResult result = execute(approvedOrder);

        // 3. Фаза commitResult: сохранение результата и фиксация идемпотентности в одной транзакции
        commitResult(order.getId(), result, event.getId());
    }

    /**
     * Фаза 1: preFlight. Только чтение из БД.
     */
    @Transactional(readOnly = true)
    public Optional<OrderEntity> preFlight(UUID orderId) {
        OrderEntity currentOrder = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Ордер не найден в БД: " + orderId));

        if (!OrderStatus.PENDING_EXECUTION.name().equals(currentOrder.getStatus())) {
            log.warn("[EXECUTION][PRE-FLIGHT] Skip execution for orderId={}, status={}",
                    orderId, currentOrder.getStatus());
            return Optional.empty();
        }

        return Optional.of(currentOrder);
    }

    /**
     * Фаза 2: execute. Чистый IO слой.
     */
    private ExecutionResult execute(ApprovedOrder approvedOrder) throws Exception {
        log.info("[EXECUTION][IO] Sending order to exchange id={}", approvedOrder.getOrderId());
        try {
            return executionEngine.execute(approvedOrder);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                log.error("[EXECUTION][IO] Timeout detected for order id={}", approvedOrder.getOrderId());
                return ExecutionResult.timeout(approvedOrder.getOrderId());
            }
            log.error("[EXECUTION][IO] Critical error for order id={}: {}", approvedOrder.getOrderId(), e.getMessage());
            throw e;
        }
    }

    /**
     * Фаза 3: commitResult. Фиксация результата в БД.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitResult(UUID orderId, ExecutionResult result, UUID eventId) {
        try {
            internalCommit(orderId, result, eventId);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.warn("[EXECUTION][LOCK] Retry commit for orderId={}", orderId);
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

        // Защита идемпотентности
        if (isFinalStatus && idempotencyService.isAlreadyProcessed(eventId)) {
            log.info("[COMMIT][IDEMPOTENT] skip duplicate processing orderId={}, eventId={}", orderId, eventId);
            return;
        }

        if (isFinalStatus) {
            log.info("[COMMIT][SKIP] already finalized orderId={}, marking event as processed", orderId);
            idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
            return;
        }

        // Обработка UNKNOWN результата через прямую сверку с биржей
        if (!result.isSuccess() && "TIMEOUT".equals(result.getErrorMessage())) {
            log.warn("[COMMIT][VERIFY] sync from Binance orderId={}", orderId);
            OrderStatusResponse binanceState = executionEngine.verifyOrder(order.getClientOrderId());

            if ("FILLED".equals(binanceState.getStatus())) {
                if (OrderStatus.REJECTED.name().equals(currentStatus) || "CANCELED".equals(currentStatus)) {
                    log.error("[DESYNC OVERRIDE] Local state overridden by Binance truth for orderId={}", orderId);
                } else {
                    log.info("[EXECUTION][RECON] Binance confirms FILLED for {}. Overwriting state.", orderId);
                }
                order.forceMarkAsFilled(binanceState.getExchangeOrderId(), binanceState.getExecutedQty());
            } else if ("PARTIALLY_FILLED".equals(binanceState.getStatus())) {
                log.info("[EXECUTION][RECON] Binance confirms PARTIALLY_FILLED for {}. Overwriting state.", orderId);
                order.markAsPartiallyFilled(binanceState.getExchangeOrderId(), binanceState.getExecutedQty());
            } else if ("CANCELED".equals(binanceState.getStatus()) || "EXPIRED".equals(binanceState.getStatus())) {
                log.warn("[EXECUTION][RECON] Binance confirms {} for {}. Rejecting.", binanceState.getStatus(), orderId);
                order.markAsRejected("Exchange status: " + binanceState.getStatus());
            } else if (OrderStatusResponse.ORDER_NOT_FOUND.equals(binanceState.getStatus())) {
                log.error("[EXECUTION][RECON] Order {} NOT_FOUND_ON_EXCHANGE. Rejecting.", orderId);
                order.markAsRejected("UNKNOWN_STATE");
            } else {
                log.warn("[EXECUTION][RECON] Order {} still in status {} on Binance. Leaving for reconciliation.", orderId, binanceState.getStatus());
                return;
            }
        } else {
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

        orderRepository.saveAndFlush(order);
        idempotencyService.markAsProcessed(eventId, "OrderExecutionHandler");
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
