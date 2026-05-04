package com.tradingbot.infrastructure.policy;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.policy.TransitionValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Адаптер, реализующий валидацию переходов через статическую политику.
 */
@Component
@RequiredArgsConstructor
public class DefaultTransitionValidator implements TransitionValidator {
    @Override
    public OrderStatus validate(OrderStatus current, OrderStatus target) {
        return OrderStateTransitionPolicy.evaluateTransition(current, target);
    }

    @Override
    public boolean isStale(OrderStatus status, java.time.Instant startedAt) {
        return OrderStateTransitionPolicy.isStale(status, startedAt);
    }

    @Override
    public boolean isTerminal(OrderStatus status) {
        return OrderStateTransitionPolicy.isTerminal(status);
    }

    @Override
    public boolean isProcessed(OrderStatus status) {
        return OrderStateTransitionPolicy.isProcessed(status);
    }

    @Override
    public java.util.Set<OrderStatus> getReconcilableStatuses() {
        return OrderStateTransitionPolicy.getReconcilableStatuses();
    }
}
