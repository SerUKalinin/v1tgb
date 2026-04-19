package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderEntity;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateStore;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderOrchestrator {

    private final OrderRepository orderRepository;
    private final ExecutionEngine executionEngine;
    private final RiskManager riskManager;
    private final RiskStateStore riskStateStore;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${oms.instance-id:instance-default}")
    private final String instanceId = UUID.randomUUID().toString();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeOrder(String orderId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(30);

        // 1. Atomic Lease/Fencing Claim
        int updated = orderRepository.claimForExecution(orderId, instanceId, expiry, now);

        if (updated == 0) {
            log.warn("[Orchestrator] Order {} already claimed or terminal. Skipping.", orderId);
            return;
        }

        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        try {
            ApprovedOrder approved = mapToApprovedOrder(order);

            // 2. Idempotent Risk Re-check (TOCTOU mitigation)
            RiskState currentState = riskStateStore.getCurrentState();
            if (!riskManager.isApprovalFresh(approved, currentState)) {
                log.warn("[Orchestrator] Risk approval stale for order {}", orderId);
                finalizeStatus(order, OrderStatus.REJECTED, null, "Stale risk approval");
                return;
            }

            // 3. Execution with Fencing Token (instanceId)
            ExecutionResult result = executionEngine.execute(approved);

            if (result.isSuccess()) {
                finalizeStatus(order, OrderStatus.FILLED, result.getExchangeOrderId(), "Filled");
            } else {
                finalizeStatus(order, OrderStatus.REJECTED, null, result.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("[Orchestrator] Critical error for order {}", orderId, e);
            finalizeStatus(order, OrderStatus.ERROR, null, e.getMessage());
        }
    }

    private void finalizeStatus(OrderEntity order, OrderStatus status, String exchangeId, String msg) {
        int updated = orderRepository.updateStatusWithFencing(
                order.getId(), status, exchangeId, instanceId, Instant.now()
        );

        if (updated == 1) {
            eventPublisher.publishEvent(OrderEvent.builder()
                    .orderId(order.getId())
                    .clientOrderId(order.getClientOrderId())
                    .status(status)
                    .message(msg)
                    .timestamp(Instant.now())
                    .build());
        } else {
            log.error("[Orchestrator] Fencing violation! Could not update order {} to {}. Lease might have expired.", 
                    order.getId(), status);
        }
    }

    private ApprovedOrder mapToApprovedOrder(OrderEntity order) {
        return ApprovedOrder.builder()
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .type(order.getType())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .riskStateVersion(order.getRiskStateVersion())
                .build();
    }
}
