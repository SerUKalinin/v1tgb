package com.tradingbot.domain.policy;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.tracing.ExecutionContext;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Доменная политика переходов состояний ордера.
 * <p>
 * Описывает формальный конечный автомат (state machine) жизненного цикла ордера
 * и гарантирует допустимость переходов между статусами.
 * <p>
 * Используется как единая точка истины (SSOT) для всех изменений OrderStatus
 * во всей системе исполнения.
 */
public class OrderStateTransitionPolicy {

    /**
     * Проверяет и пропускает ExecutionContext без изменения identity,
     * если переход состояния допустим.
     */
    public static ExecutionContext validateAndPassThrough(ExecutionContext context, OrderStatus current, OrderStatus target) {
        if (current == target) return context;

        if (!canTransition(current, target)) {
            throw new IllegalStateException(String.format(
                    "IDENTITY-STRICT-VIOLATION: Transition from %s to %s is forbidden. Identity: %s",
                    current, target, context.identity().signalId()));
        }

        return context;
    }

    /**
     * Граф допустимых переходов состояний ордера.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> STATE_GRAPH = new EnumMap<>(OrderStatus.class);

    static {

        STATE_GRAPH.put(OrderStatus.NEW, Set.of(OrderStatus.PENDING_EXECUTION, OrderStatus.REJECTED));

        STATE_GRAPH.put(OrderStatus.PENDING_EXECUTION, Set.of(
                OrderStatus.EXECUTING,
                OrderStatus.CANCELED,
                OrderStatus.REJECTED
        ));

        STATE_GRAPH.put(OrderStatus.EXECUTING, Set.of(
                OrderStatus.EXECUTING,
                OrderStatus.SENT_TO_EXCHANGE,
                OrderStatus.FILLED,
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.REJECTED,
                OrderStatus.UNKNOWN,
                OrderStatus.CANCELED
        ));

        STATE_GRAPH.put(OrderStatus.SENT_TO_EXCHANGE, Set.of(
                OrderStatus.FILLED,
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.REJECTED,
                OrderStatus.CANCELED
        ));

        STATE_GRAPH.put(OrderStatus.PARTIALLY_FILLED, Set.of(
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCELED,
                OrderStatus.REJECTED
        ));

        STATE_GRAPH.put(OrderStatus.UNKNOWN, Set.of(
                OrderStatus.FILLED,
                OrderStatus.REJECTED,
                OrderStatus.EXECUTING,
                OrderStatus.RECOVERING
        ));

        STATE_GRAPH.put(OrderStatus.RECOVERING, Set.of(
                OrderStatus.FILLED,
                OrderStatus.REJECTED,
                OrderStatus.CANCELED,
                OrderStatus.UNKNOWN
        ));

        STATE_GRAPH.put(OrderStatus.FILLED, Collections.emptySet());
        STATE_GRAPH.put(OrderStatus.REJECTED, Collections.emptySet());
        STATE_GRAPH.put(OrderStatus.CANCELED, Collections.emptySet());
        STATE_GRAPH.put(OrderStatus.ERROR, Collections.emptySet());
    }

    /**
     * Результат проверки перехода состояния.
     */
    public enum TransitionDecision {
        ALLOWED, DENIED
    }

    /**
     * Проверяет допустимость перехода между состояниями.
     */
    public static TransitionDecision evaluate(OrderStatus current, OrderStatus target) {
        if (current == target) return TransitionDecision.ALLOWED;
        return canTransition(current, target)
                ? TransitionDecision.ALLOWED
                : TransitionDecision.DENIED;
    }

    /**
     * Валидирует переход состояния с выбросом исключения при нарушении политики.
     */
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

    /**
     * Проверяет возможность перехода между состояниями.
     */
    public static boolean canTransition(OrderStatus current, OrderStatus target) {
        if (current == target) return true;
        return STATE_GRAPH.getOrDefault(current, Collections.emptySet()).contains(target);
    }

    /**
     * Проверяет валидность состояния.
     */
    public static void validateState(OrderStatus status) {
        if (status == null || !STATE_GRAPH.containsKey(status)) {
            throw new IllegalStateException("STATE MUST GO THROUGH POLICY: Invalid or null Order status");
        }
    }

    /**
     * Маппинг результата исполнения биржи в доменный статус ордера.
     */
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

    /**
     * Проверяет, является ли состояние терминальным.
     */
    public static boolean isTerminal(OrderStatus status) {
        return STATE_GRAPH.containsKey(status) && STATE_GRAPH.get(status).isEmpty();
    }

    /**
     * Проверяет устаревание состояния EXECUTING.
     */
    public static boolean isStale(OrderStatus status, Instant startedAt) {
        if (status == OrderStatus.EXECUTING && startedAt != null) {
            return startedAt.isBefore(Instant.now().minus(java.time.Duration.ofSeconds(30)));
        }
        return false;
    }

    /**
     * Проверяет, обработан ли ордер.
     */
    public static boolean isProcessed(OrderStatus status) {
        return status != OrderStatus.NEW && status != OrderStatus.PENDING_EXECUTION;
    }

    /**
     * Проверяет готовность ордера к исполнению.
     */
    public static boolean isReadyForExecution(OrderStatus status) {
        return status == OrderStatus.PENDING_EXECUTION;
    }

    /**
     * Проверяет активность ордера.
     */
    public static boolean isActive(OrderStatus status) {
        return !isTerminal(status) && status != OrderStatus.NEW;
    }

    /**
     * Возвращает состояния, подлежащие реконсиляции.
     */
    public static Set<OrderStatus> getReconcilableStatuses() {
        return Set.of(
                OrderStatus.PENDING_EXECUTION,
                OrderStatus.EXECUTING,
                OrderStatus.SENT_TO_EXCHANGE,
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.UNKNOWN,
                OrderStatus.RECOVERING
        );
    }

    /**
     * Проверяет, подлежит ли состояние реконсиляции.
     */
    public static boolean isReconcilable(OrderStatus status) {
        return getReconcilableStatuses().contains(status);
    }

    /**
     * Валидирует и возвращает новое состояние при переходе.
     */
    public static OrderStatus evaluateTransition(OrderStatus current, OrderStatus target) {
        if (current == target) return current;
        if (!canTransition(current, target)) {
            throw new IllegalStateException(String.format(
                    "Transition from %s to %s is forbidden by Policy", current, target));
        }
        return target;
    }
}