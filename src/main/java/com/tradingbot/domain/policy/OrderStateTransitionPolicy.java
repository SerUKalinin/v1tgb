package com.tradingbot.domain.policy;

import com.tradingbot.common.enums.OrderStatus;

import com.tradingbot.tracing.ExecutionContext;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class OrderStateTransitionPolicy {

    public static ExecutionContext validateAndPassThrough(ExecutionContext context, OrderStatus current, OrderStatus target) {
        if (current == target) return context;
        
        if (!canTransition(current, target)) {
            throw new IllegalStateException(String.format(
                "IDENTITY-STRICT-VIOLATION: Transition from %s to %s is forbidden. Identity: %s", 
                current, target, context.identity().signalId()));
        }
        
        // Возвращаем контекст без модификации identity (identity-pass-through)
        return context;
    }

    private static final Map<OrderStatus, Set<OrderStatus>> STATE_GRAPH = new EnumMap<>(OrderStatus.class);

    static {
        // NEW -> PENDING_EXECUTION (одобрен), REJECTED (отклонен рисками)
        STATE_GRAPH.put(OrderStatus.NEW, Set.of(OrderStatus.PENDING_EXECUTION, OrderStatus.REJECTED));

        // PENDING_EXECUTION -> EXECUTING (захват), CANCELED (отмена до отправки), REJECTED (reconciliation)
        STATE_GRAPH.put(OrderStatus.PENDING_EXECUTION, Set.of(OrderStatus.EXECUTING, OrderStatus.CANCELED, OrderStatus.REJECTED));        // EXECUTING -> EXECUTING (идемпотентность), SENT_TO_EXCHANGE, FILLED, PARTIALLY_FILLED, REJECTED, UNKNOWN, CANCELED
        STATE_GRAPH.put(OrderStatus.EXECUTING, Set.of(
            OrderStatus.EXECUTING, 
            OrderStatus.SENT_TO_EXCHANGE, 
            OrderStatus.FILLED, 
            OrderStatus.PARTIALLY_FILLED, 
            OrderStatus.REJECTED, 
            OrderStatus.UNKNOWN,
            OrderStatus.CANCELED
        ));

        // SENT_TO_EXCHANGE -> FILLED, PARTIALLY_FILLED, REJECTED, CANCELED
        STATE_GRAPH.put(OrderStatus.SENT_TO_EXCHANGE, Set.of(
            OrderStatus.FILLED, 
            OrderStatus.PARTIALLY_FILLED, 
            OrderStatus.REJECTED, 
            OrderStatus.CANCELED
        ));

        // PARTIALLY_FILLED -> PARTIALLY_FILLED (доп. исполнение), FILLED, CANCELED, REJECTED
        STATE_GRAPH.put(OrderStatus.PARTIALLY_FILLED, Set.of(
            OrderStatus.PARTIALLY_FILLED, 
            OrderStatus.FILLED, 
            OrderStatus.CANCELED, 
            OrderStatus.REJECTED
        ));

        // UNKNOWN -> FILLED, REJECTED, EXECUTING (retry / recovery), RECOVERING
        STATE_GRAPH.put(OrderStatus.UNKNOWN, Set.of(
            OrderStatus.FILLED,
            OrderStatus.REJECTED,
            OrderStatus.EXECUTING,
            OrderStatus.RECOVERING
        ));

        // RECOVERING -> FILLED, REJECTED, CANCELED, UNKNOWN
        STATE_GRAPH.put(OrderStatus.RECOVERING, Set.of(
            OrderStatus.FILLED,
            OrderStatus.REJECTED,
            OrderStatus.CANCELED,
            OrderStatus.UNKNOWN
        ));

        // TERMINAL STATES (пустые сеты - переходы запрещены)        STATE_GRAPH.put(OrderStatus.FILLED, Collections.emptySet());
        STATE_GRAPH.put(OrderStatus.REJECTED, Collections.emptySet());
        STATE_GRAPH.put(OrderStatus.CANCELED, Collections.emptySet());
        STATE_GRAPH.put(OrderStatus.ERROR, Collections.emptySet());
    }

    public enum TransitionDecision {
        ALLOWED, DENIED
    }

    public static TransitionDecision evaluate(OrderStatus current, OrderStatus target) {
        if (current == target) return TransitionDecision.ALLOWED;
        return canTransition(current, target) ? TransitionDecision.ALLOWED : TransitionDecision.DENIED;
    }

    public static void requestTransition(OrderStatus current, OrderStatus target) {
        if (current == target) return;
        
        if (target == OrderStatus.UNKNOWN && current != OrderStatus.EXECUTING) {
            throw new IllegalStateException(String.format(
                "STATE-POLICY-VIOLATION: Transition to UNKNOWN is only allowed from EXECUTING. Current: %s", current));
        }

        if (!canTransition(current, target)) {
            throw new IllegalStateException(String.format(
                "STATE MUST GO THROUGH POLICY: Transition from %s to %s is forbidden by Formal State Graph", 
                current, target));
        }
    }
    public static boolean canTransition(OrderStatus current, OrderStatus target) {
        if (current == target) return true;
        return STATE_GRAPH.getOrDefault(current, Collections.emptySet()).contains(target);
    }

    public static void validateState(OrderStatus status) {
        if (status == null || !STATE_GRAPH.containsKey(status)) {
            throw new IllegalStateException("STATE MUST GO THROUGH POLICY: Invalid or null Order status");
        }
    }

    public static OrderStatus mapExecutionResult(com.tradingbot.domain.model.ExecutionResult.Status resultStatus) {
        return switch (resultStatus) {
            case FILLED -> OrderStatus.FILLED;
            case PARTIALLY_FILLED -> OrderStatus.PARTIALLY_FILLED;
            case ACCEPTED -> OrderStatus.SENT_TO_EXCHANGE;
            case REJECTED -> OrderStatus.REJECTED;
            case CANCELED -> OrderStatus.CANCELED;
            case EXCHANGE_STATE_UNKNOWN -> OrderStatus.UNKNOWN;
        };
    }

    public static boolean isTerminal(OrderStatus status) {
        // Терминальное состояние - то, из которого нет исходящих переходов в графе
        return STATE_GRAPH.containsKey(status) && STATE_GRAPH.get(status).isEmpty();
    }

    public static boolean isStale(OrderStatus status, Instant startedAt) {
        if (status == OrderStatus.EXECUTING && startedAt != null) {
            return startedAt.isBefore(Instant.now().minus(java.time.Duration.ofSeconds(30)));
        }
        return false;
    }

    public static boolean isProcessed(OrderStatus status) {
        return status != OrderStatus.NEW && status != OrderStatus.PENDING_EXECUTION;
    }

    public static boolean isReadyForExecution(OrderStatus status) {
        return status == OrderStatus.PENDING_EXECUTION;
    }

    public static boolean isActive(OrderStatus status) {
        return !isTerminal(status) && status != OrderStatus.NEW;
    }
    public static Set<OrderStatus> getReconcilableStatuses() {
        return Set.of(OrderStatus.PENDING_EXECUTION, OrderStatus.EXECUTING, OrderStatus.SENT_TO_EXCHANGE, OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN, OrderStatus.RECOVERING);
    }
    public static boolean isReconcilable(OrderStatus status) {
        return getReconcilableStatuses().contains(status);
    }
    public static OrderStatus evaluateTransition(OrderStatus current, OrderStatus target) {
        if (current == target) return current;
        if (!canTransition(current, target)) {
            throw new IllegalStateException(String.format(
                "Transition from %s to %s is forbidden by Policy", current, target));
        }
        return target;
    }}
