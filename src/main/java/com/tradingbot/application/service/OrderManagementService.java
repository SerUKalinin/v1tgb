package com.tradingbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.OrderEntity;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.order.OrderEvent;
import com.tradingbot.domain.order.OrderStateMachine;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskStateStore;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Idempotent Transactional Orchestrator for Order Management.
 *
 * FIX: All status transitions go through OrderStateMachine.
 * FIX: OrderEntity.id is set explicitly before save.
 * FIX: Uses OrderStatus.APPROVED (now exists in enum).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderManagementService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final RiskManager riskManager;
    private final RiskStateStore riskStateStore;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processSignal(Signal signal) {
        // 1. IDEMPOTENCY CHECK
        if (orderRepository.existsByClientOrderId(signal.getClientOrderId())) {
            log.warn("[OMS] Duplicate signal detected, skipping: {}", signal.getClientOrderId());
            return;
        }

        // 2. CREATE INITIAL ORDER (NEW)
        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID().toString())
                .clientOrderId(signal.getClientOrderId())
                .symbol(signal.getSymbol())
                .strategyId(signal.getStrategyId())
                .side(signal.getSide())
                .status(OrderStatus.NEW)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        orderRepository.save(order);
        // 3. RISK CHECK
        var currentState = riskStateStore.getCurrentState();
        var decision = riskManager.approveSignal(signal, currentState);

        if (decision.isEmpty()) {
            log.warn("[OMS] Signal REJECTED by RiskManager: {}", signal.getClientOrderId());
            // FIX: transition through FSM
            transition(order, OrderEvent.RISK_CHECK_FAILED);
            return;
        }

        ApprovedOrder approved = decision.get();

        // 4. TRANSITION: NEW → ACCEPTED → APPROVED
        transition(order, OrderEvent.RISK_CHECK_PASSED);   // → ACCEPTED
        order.setQuantity(approved.getQuantity());
        order.setPrice(approved.getPrice());
        order.setRiskStateVersion(approved.getRiskStateVersion());
        transition(order, OrderEvent.RISK_SIZED);           // → APPROVED
        orderRepository.save(order);

        // 5. ATOMIC OUTBOX ENTRY (same transaction — guaranteed at-least-once)
        persistOutboxEvent(order, approved);

        // 6. TRANSITION: APPROVED → PENDING_EXECUTION
        transition(order, OrderEvent.OUTBOX_COMMITTED);    // → PENDING_EXECUTION
        orderRepository.save(order);

        log.info("[OMS] Order {} ready for execution (status={})",
                order.getClientOrderId(), order.getStatus());
    }

    private void transition(OrderEntity order, OrderEvent event) {
        OrderStatus current = order.getStatus();
        OrderStatus next = OrderStateMachine.getNextStatus(current, event);
        order.setStatus(next);
        order.setUpdatedAt(Instant.now());
    }
    private void persistOutboxEvent(OrderEntity order, ApprovedOrder approved) {
        try {
            OutboxEventEntity event = OutboxEventEntity.builder()
                    .eventId(UUID.randomUUID().toString())
                    .clientOrderId(order.getClientOrderId())
                    .type("ORDER_APPROVED")          // FIX: field is `type`, not `eventType`
                    .payload(objectMapper.writeValueAsString(approved))
                    .status("PENDING")
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("[OMS] Failed to create outbox entry for {}", order.getClientOrderId(), e);
            throw new RuntimeException("Outbox persistence failed — rolling back transaction", e);
        }
    }
}