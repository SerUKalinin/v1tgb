package com.tradingbot.domain.execution;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateMachine {

    private final OrderRepository orderRepository;

    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = Map.of(
        OrderStatus.NEW, EnumSet.of(OrderStatus.APPROVED, OrderStatus.REJECTED),
        OrderStatus.APPROVED, EnumSet.of(OrderStatus.EXECUTING, OrderStatus.REJECTED),
        OrderStatus.EXECUTING, EnumSet.of(OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.ERROR)
    );

    @Transactional
    public boolean transitionTo(String orderId, OrderStatus targetStatus, String exchangeOrderId, String nodeId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        OrderStatus currentStatus = order.getStatus();

        if (!isValidTransition(currentStatus, targetStatus)) {
            log.error("[FSM] Invalid transition: {} -> {} for order {}", currentStatus, targetStatus, orderId);
            return false;
        }

        int updated = orderRepository.updateStatusWithFencing(
                orderId,
                targetStatus,
                exchangeOrderId,
                nodeId,
                Instant.now()
        );

        if (updated > 0) {
            log.info("[FSM] Order {} transitioned: {} -> {}", orderId, currentStatus, targetStatus);
            return true;
        }

        log.warn("[FSM] Transition failed due to fencing/concurrency for order {}", orderId);
        return false;
    }

    private boolean isValidTransition(OrderStatus from, OrderStatus to) {
        if (from == to) return true;
        Set<OrderStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
