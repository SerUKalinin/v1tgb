package com.tradingbot.domain.execution;

import com.tradingbot.domain.execution.ExecutionOwnershipException;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ExecutionOwnershipValidator {

    public static void validateExecutionOwnership(Order order, UUID executionId) {
        if (order == null || executionId == null) {
            throw new ExecutionOwnershipException("Order and executionId must be provided");
        }
        if (order.getExecutionId() == null) {
            throw new ExecutionOwnershipException(String.format("Order %s was never claimed", order.getId()));
        }
        if (!order.getExecutionId().equals(executionId)) {
            throw new ExecutionOwnershipException(String.format("Order %s ownership mismatch: expected=%s actual=%s", order.getId(), executionId, order.getExecutionId()));
        }
        if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
            throw new ExecutionOwnershipException(String.format("Order %s already terminal (%s)", order.getId(), order.getStatus()));
        }
    }

    public static void validateMutationAllowedOrThrow(Order order, UUID executionId) {
        validateExecutionOwnership(order, executionId);
    }
}