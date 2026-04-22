package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.OrderEvent;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.risk.RiskManager;import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.dao.DataIntegrityViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderManagementService {

    private final OrderRepository orderRepository;
    private final TradeService tradeService;
    private final PositionService positionService;
    private final RiskManager riskManager;
    private final ExecutionEngine executionEngine;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, Boolean> processedSignals = new ConcurrentHashMap<>();

    // =========================================================
    // ENTRY POINT (DISABLED)
    // =========================================================

    @Transactional
    @Deprecated
    public ExecutionResult executeOrder(OrderRequest request) {
        throw new IllegalStateException("Use SignalEvent pipeline only");
    }

    // =========================================================
    // SIGNAL ENTRY POINT
    // =========================================================

    @EventListener
    @Transactional
    public void onSignal(SignalEvent event) {

        if (event.getType() == SignalType.HOLD) {
            return;
        }

        // FIX 2: stable dedup key
        String dedupeId = buildDedupeKey(event);

        if (processedSignals.putIfAbsent(dedupeId, Boolean.TRUE) != null) {
            log.debug("[OMS] Duplicate signal skipped: {}", dedupeId);
            return;
        }

        log.info("[OMS] Processing signal {} {} @ {}",
                event.getType(),
                event.getSymbol(),
                event.getPrice());

        processSignal(event);
    }

    private String buildDedupeKey(SignalEvent event) {
        return event.getSymbol()
                + ":"
                + event.getStrategyId()
                + ":"
                + event.getType()
                + ":"
                + (event.getCandleTime() != null
                ? event.getCandleTime().toEpochMilli()
                : System.currentTimeMillis());
    }

    // =========================================================
    // CORE LOGIC
    // =========================================================

    private ExecutionResult processSignal(SignalEvent signal) {

        // POSITION GUARD
        Position position = positionService.getPosition(
                signal.getSymbol(),
                signal.getStrategyId()
        );

        if (position != null && position.isOpen()) {

            boolean sameDirection =
                    (position.getNetQuantity().signum() > 0 && signal.getType() == SignalType.BUY)
                            || (position.getNetQuantity().signum() < 0 && signal.getType() == SignalType.SELL);

            if (sameDirection) {
                log.warn("[OMS] Skip signal: already in position {} {}", signal.getSymbol(), signal.getType());
                return ExecutionResult.failure(null, "Already in position");
            }
        }
        // RISK CHECK
        Optional<com.tradingbot.domain.risk.ApprovedOrder> approvedOpt =
                riskManager.approveSignal(signal);

        if (approvedOpt.isEmpty()) {
            log.warn("[OMS] Signal rejected by Risk: {}", signal);
            return ExecutionResult.failure(null, "Rejected by Risk Engine");
        }

        com.tradingbot.domain.risk.ApprovedOrder approved = approvedOpt.get();

        // DB IDEMPOTENCY (FIX 3) - DB-first approach
        OrderEntity order;
        try {
            order = createOrderEntity(approved);
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            log.warn("[OMS] Duplicate order detected in DB: {}. Recovering existing.", approved.getClientOrderId());
            return orderRepository.findByClientOrderId(approved.getClientOrderId())
                    .map(existing -> ExecutionResult.builder()
                            .orderId(existing.getId())
                            .clientOrderId(existing.getClientOrderId())
                            .exchangeOrderId(existing.getExchangeOrderId())
                            .symbol(existing.getSymbol())
                            .side(existing.getSide())
                            .executedQty(existing.getQuantity())
                            .success(true)
                            .build())
                    .orElseThrow(() -> new IllegalStateException("Order should exist but not found after collision", e));
        }
        publishOrderEvent(order, "Approved by Risk Engine");

        return executeInternal(order, approved);
    }
    // =========================================================
    // EXECUTION
    // =========================================================

    private ExecutionResult executeInternal(
            OrderEntity order,
            com.tradingbot.domain.risk.ApprovedOrder approved
    ) {
        try {

            if (!riskManager.isApprovalFresh(approved)) {
                log.error("[OMS] Stale approval: {}", order.getId());

                order.setStatus(OrderStatus.REJECTED.name());
                orderRepository.save(order);

                publishOrderEvent(order, "Rejected: stale approval");

                return ExecutionResult.failure(order.getId(), "Stale approval");
            }

            ExecutionResult result = executionEngine.execute(approved);

            if (result.isSuccess()) {

                order.setStatus(OrderStatus.FILLED.name());
                order.setExchangeOrderId(result.getExchangeOrderId());

                orderRepository.save(order);

                publishOrderEvent(order, "FILLED");

                return result;
            }

            order.setStatus(OrderStatus.REJECTED.name());
            orderRepository.save(order);

            publishOrderEvent(order, "FAILED: " + result.getErrorMessage());

            return result;

        } catch (Exception e) {

            log.error("[OMS] Execution error {}", order.getId(), e);

            order.setStatus(OrderStatus.ERROR.name());
            orderRepository.save(order);

            publishOrderEvent(order, "ERROR: " + e.getMessage());

            return ExecutionResult.failure(order.getId(), e.getMessage());
        }
    }

    // =========================================================
    // CLOSE POSITION
    // =========================================================

    @Transactional
    public void closePosition(Position position) {

        if (!position.isOpen()) {
            log.warn("[OMS] Cannot close position {}", position.getSymbol());
            return;
        }

        SignalType type = position.getNetQuantity().signum() > 0
                ? SignalType.SELL
                : SignalType.BUY;

        SignalEvent closeSignal = SignalEvent.builder()
                .symbol(position.getSymbol())
                .strategyId(position.getStrategyId())
                .type(type)
                .price(BigDecimal.ZERO)
                .candleTime(Instant.now())
                .build();

        processSignal(closeSignal);
    }
    // =========================================================
    // ENTITY CREATION
    // =========================================================

    private OrderEntity createOrderEntity(
            com.tradingbot.domain.risk.ApprovedOrder approved
    ) {
        return OrderEntity.builder()
                .id(approved.getOrderId())
                .clientOrderId(approved.getClientOrderId())
                .symbol(approved.getSymbol())
                .strategyId(approved.getStrategyId())
                .side(approved.getSide())
                .type(approved.getType())
                .quantity(approved.getQuantity())
                .price(approved.getPrice())
                .stopLoss(approved.getStopLoss())
                .takeProfit(approved.getTakeProfit())
                .status(OrderStatus.VALIDATED.name())
                .createdAt(Instant.now())
                .build();
    }

    // =========================================================
    // EVENTS
    // =========================================================

    private void publishOrderEvent(OrderEntity order, String message) {

        eventPublisher.publishEvent(
                OrderEvent.builder()
                        .orderId(order.getId())
                        .clientOrderId(order.getClientOrderId())
                        .status(OrderStatus.valueOf(order.getStatus()))
                        .symbol(order.getSymbol())
                        .side(order.getSide())
                        .quantity(order.getQuantity())
                        .message(message)
                        .timestamp(Instant.now())
                        .build()
        );
    }
}