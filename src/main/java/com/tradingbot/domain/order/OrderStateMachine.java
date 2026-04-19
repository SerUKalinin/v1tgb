package com.tradingbot.domain.order;

import com.tradingbot.common.enums.OrderStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Centralised FSM validator for order state transitions.
 *
 * All status changes in OMS MUST go through getNextStatus().
 * Direct setStatus() calls without FSM validation are forbidden.
 *
 * FSM:
 *   NEW ──────────────────┬─ RISK_CHECK_PASSED ──► ACCEPTED
 *                         └─ RISK_CHECK_FAILED ──► REJECTED
 *   ACCEPTED ─────────────── RISK_SIZED ──────────► APPROVED
 *   APPROVED ─────────────── OUTBOX_COMMITTED ────► PENDING_EXECUTION
 *   PENDING_EXECUTION ────── EXECUTION_STARTED ───► EXECUTING
 *   EXECUTING ────────────┬─ EXECUTION_SUCCESS ───► FILLED
 *                         ├─ EXECUTION_FAILED ────► REJECTED
 *                         └─ EXTERNAL_SYNC ───────► FILLED
 */
public class OrderStateMachine {

    private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS =
            new EnumMap<>(OrderStatus.class);

    static {
        // NEW
        configure(OrderStatus.NEW, OrderEvent.RISK_CHECK_PASSED, OrderStatus.ACCEPTED);
        configure(OrderStatus.NEW, OrderEvent.RISK_CHECK_FAILED, OrderStatus.REJECTED);

        // ACCEPTED → APPROVED (Risk Engine sized the quantity)
        configure(OrderStatus.ACCEPTED, OrderEvent.RISK_SIZED, OrderStatus.APPROVED);

        // APPROVED → PENDING_EXECUTION (Outbox entry committed)
        configure(OrderStatus.APPROVED, OrderEvent.OUTBOX_COMMITTED, OrderStatus.PENDING_EXECUTION);

        // PENDING_EXECUTION → EXECUTING (distributed lock claimed)
        configure(OrderStatus.PENDING_EXECUTION, OrderEvent.EXECUTION_STARTED, OrderStatus.EXECUTING);

        // EXECUTING → terminal
        configure(OrderStatus.EXECUTING, OrderEvent.EXECUTION_SUCCESS, OrderStatus.FILLED);
        configure(OrderStatus.EXECUTING, OrderEvent.EXECUTION_FAILED,  OrderStatus.REJECTED);
        configure(OrderStatus.EXECUTING, OrderEvent.EXTERNAL_SYNC,     OrderStatus.FILLED);
    }

    private static void configure(OrderStatus from, OrderEvent event, OrderStatus to) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(OrderEvent.class)).put(event, to);
    }

    /**
     * Returns the next valid status for a given transition.
     *
     * @throws IllegalOrderStateTransitionException if the transition is not in the FSM
     */
    public static OrderStatus getNextStatus(OrderStatus current, OrderEvent event) {
        Map<OrderEvent, OrderStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.containsKey(event)) {
            throw new IllegalOrderStateTransitionException(
                    String.format("Invalid transition from %s via %s", current, event));
        }
        return allowed.get(event);
    }

    /** Terminal states cannot have further transitions. */
    public static boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FILLED || status == OrderStatus.REJECTED;
    }

    /** All states from which execution can be retried. */
    public static boolean isRecoverable(OrderStatus status) {
        return status == OrderStatus.PENDING_EXECUTION || status == OrderStatus.EXECUTING;
    }

    public static class IllegalOrderStateTransitionException extends RuntimeException {
        public IllegalOrderStateTransitionException(String message) {
            super(message);
        }
    }
}