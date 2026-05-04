package com.tradingbot.domain.policy;

import com.tradingbot.common.enums.OrderStatus;
import java.time.Instant;
import java.util.Set;

/**
 * Порт для валидации переходов состояний ордера.
 */
public interface TransitionValidator {
    OrderStatus validate(OrderStatus current, OrderStatus target);
    boolean isStale(OrderStatus status, Instant startedAt);
    boolean isTerminal(OrderStatus status);
    boolean isProcessed(OrderStatus status);
    Set<OrderStatus> getReconcilableStatuses();
}
