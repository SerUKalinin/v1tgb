package com.tradingbot.application.service;

import com.tradingbot.domain.event.OrderReadyForExecutionEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.OrderEntity;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExecutionDispatcher {
    private final ExecutionEngine executionEngine;
    private final OrderRepository orderRepository;
    private final OrderFencingService fencingService;
    private final com.tradingbot.domain.risk.RiskManager riskManager;
    private final com.tradingbot.domain.risk.RiskStateStore riskStateStore;
    private final com.tradingbot.domain.execution.OrderStateMachine stateMachine;
    
    private final String nodeId = UUID.randomUUID().toString();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ExecutionEngine getExecutionEngine() {
        return executionEngine;
    }

    public boolean dispatch(OrderReadyForExecutionEvent event) {
        log.info("[EXECUTION-DISPATCHER] Dispatching order {}", event.orderId());
        
        OrderEntity order = orderRepository.findById(event.orderId()).orElse(null);
        if (order == null) {
            log.error("[EXECUTION-DISPATCHER] Order not found: {}", event.orderId());
            return false;
        }

        // 1. Acquire Lease
        if (!fencingService.tryAcquire(order.getId(), nodeId)) {
            log.warn("[EXECUTION-DISPATCHER] Could not acquire lease for order {}", order.getId());
            return false;
        }

        // 2. Start Heartbeat (30s interval for 2m lease)
        var heartbeat = scheduler.scheduleAtFixedRate(
            () -> {
                if (!fencingService.extendLease(order.getId(), nodeId)) {
                    log.error("[FENCING] Lost lease for order {}", order.getId());
                }
            },
            30, 30, TimeUnit.SECONDS
        );

        try {
            ApprovedOrder approvedOrder = mapToApproved(order);

            // TOCTOU Fix: Validate risk decision freshness before execution
            var currentState = riskStateStore.getState();
            if (!riskManager.isApprovalFresh(approvedOrder, currentState)) {
                log.error("[EXECUTION-DISPATCHER] Risk decision is stale for order {}. Rejecting.", order.getId());
                stateMachine.transitionTo(
                    order.getId(),
                    com.tradingbot.common.enums.OrderStatus.REJECTED,
                    "STALE_RISK_DECISION",
                    nodeId
                );
                return false;
            }

            var result = executionEngine.execute(approvedOrder);
            
            // 3. Update status via FSM
            return stateMachine.transitionTo(
                order.getId(), 
                result.isSuccess() ? com.tradingbot.common.enums.OrderStatus.FILLED : com.tradingbot.common.enums.OrderStatus.REJECTED,
                result.getExchangeOrderId(),
                nodeId
            );
        } catch (Exception e) {
            log.error("[EXECUTION-DISPATCHER] Critical execution error for order {}", order.getId(), e);
            return false;
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ApprovedOrder mapToApproved(OrderEntity order) {
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
