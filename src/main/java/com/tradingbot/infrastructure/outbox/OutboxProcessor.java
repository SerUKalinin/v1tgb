package com.tradingbot.infrastructure.outbox;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.OrderCompensationService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor {

    private static final String ORDER_CREATED = "ORDER_CREATED";

    private final OutboxEventRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final ExecutionEngine executionEngine;
    private final ExchangeOrderQueryService exchangeQueryService;
    private final OrderCompensationService compensationService;
    private final OutboxRetryPolicy retryPolicy;

    // =========================
    // MAIN LOOP
    // =========================

    @Scheduled(fixedDelay = 500)
    public void processOutbox() {

        List<OutboxEventEntity> events = claimBatch();
        if (events.isEmpty()) return;

        for (OutboxEventEntity event : events) {
            try {
                process(event);
            } catch (Exception e) {
                log.error("[OUTBOX] failure eventId={}", event.getId(), e);
                markFailed(event.getId());
            }
        }
    }

    // =========================
    // CLAIM
    // =========================

    @Transactional
    protected List<OutboxEventEntity> claimBatch() {

        List<OutboxEventEntity> events = outboxRepository.claimBatch();

        events.forEach(e -> {
            e.setStatus(OutboxStatus.PROCESSING);
            e.setUpdatedAt(Instant.now());
        });

        return outboxRepository.saveAll(events);
    }

    // =========================
    // ORCHESTRATION ONLY
    // =========================

    private void process(OutboxEventEntity event) {

        if (!ORDER_CREATED.equals(event.getEventType())) {
            markProcessed(event.getId());
            return;
        }

        OrderEntity order = loadOrder(event);
        if (order == null) {
            markProcessed(event.getId());
            return;
        }

        if (!isSafeToExecute(event, order)) {
            markProcessed(event.getId());
            return;
        }

        ApprovedOrder approved = map(order);

        ExecutionResult result = executionEngine.execute(approved);

        finalize(event, order, result);
    }

    // =========================
    // IDENTITY GUARD
    // =========================

    private boolean isSafeToExecute(OutboxEventEntity event, OrderEntity order) {

        if (!OrderStatus.PENDING_EXECUTION.name().equals(order.getStatus())) {
            return false;
        }

        if (event.getRetryCount() == 0) {
            return true;
        }

        // Если сервис не внедрен или отсутствует, считаем выполнение безопасным (fallback)
        if (exchangeQueryService == null) {
            return true;
        }

        try {
            if (exchangeQueryService.isOrderAlreadyExecuted(order.getClientOrderId())) {
                log.warn("[GUARD] order already executed on exchange {}", order.getId());
                return false;
            }
        } catch (Exception e) {
            log.error("[GUARD] failed to check exchange status for order {}, assuming safe", order.getId(), e);
            return true;
        }

        return true;
    }
    // =========================
    // FINALIZE
    // =========================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void finalize(OutboxEventEntity event,
                            OrderEntity order,
                            ExecutionResult result) {

        if (result.isSuccess()) {

            handleSuccess(order, result);

        } else {

            handleFailure(order, result);
        }

        orderRepository.save(order);

        markProcessed(event.getId());
    }

    // =========================
    // BUSINESS LOGIC ISOLATED
    // =========================

    private void handleSuccess(OrderEntity order, ExecutionResult result) {

        BigDecimal executed = safe(result.getExecutedQty());
        BigDecimal qty = order.getQuantity();

        if (executed.compareTo(BigDecimal.ZERO) > 0 && executed.compareTo(qty) < 0) {

            order.setStatus(OrderStatus.PARTIALLY_FILLED.name());

            compensationService.releasePartial(order, executed);

        } else {

            order.setStatus(OrderStatus.FILLED.name());
        }

        order.setExchangeOrderId(result.getExchangeOrderId());
    }

    private void handleFailure(OrderEntity order, ExecutionResult result) {

        order.setStatus(OrderStatus.REJECTED.name());

        compensationService.releaseFull(order, result.getErrorMessage());
    }

    // =========================
    // OUTBOX STATE MACHINE
    // =========================

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markProcessed(UUID eventId) {

        OutboxEventEntity event = outboxRepository.findById(eventId)
                .orElseThrow();

        event.setStatus(OutboxStatus.PROCESSED);
        event.setProcessedAt(Instant.now());

        outboxRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailed(UUID eventId) {

        OutboxEventEntity event = outboxRepository.findById(eventId)
                .orElseThrow();

        event.setRetryCount(event.getRetryCount() + 1);
        event.setUpdatedAt(Instant.now());

        if (!retryPolicy.shouldRetry(event)) {
            event.setStatus(OutboxStatus.DEAD);
        } else {
            event.setStatus(OutboxStatus.FAILED);
        }

        outboxRepository.save(event);
    }

    // =========================
    // LOAD + MAP
    // =========================

    private OrderEntity loadOrder(OutboxEventEntity event) {

        return orderRepository.findById(event.getAggregateId())
                .orElseThrow();
    }

    private ApprovedOrder map(OrderEntity order) {

        return ApprovedOrder.builder()
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .strategyId(order.getStrategyId())
                .side(order.getSide())
                .type(order.getType())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .stopLoss(order.getStopLoss())
                .takeProfit(order.getTakeProfit())
                .build();
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}