package com.tradingbot.domain.execution;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Валидатор владения execution для ордера.
 * <p>
 * Проверяет, что текущий execution имеет право изменять/обрабатывать ордер,
 * а также контролирует корректность состояния ордера в рамках state machine.
 * Используется для защиты от race condition и повторной обработки ордера.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ExecutionOwnershipValidator {

    /**
     * Проверяет соответствие execution владельца ордера и корректность его состояния.
     *
     * @param order        ордер, находящийся в обработке
     * @param executionId  идентификатор текущего execution
     * @throws ExecutionOwnershipException если нарушено владение или состояние ордера некорректно
     */
    public static void validateExecutionOwnership(Order order, UUID executionId) {
        if (order == null || executionId == null) {
            throw new ExecutionOwnershipException("Order and executionId must be provided");
        }

        if (order.getExecutionId() == null) {
            throw new ExecutionOwnershipException(
                    String.format("Order %s was never claimed", order.getId())
            );
        }

        if (!order.getExecutionId().equals(executionId)) {
            throw new ExecutionOwnershipException(
                    String.format(
                            "Order %s ownership mismatch: expected=%s actual=%s",
                            order.getId(),
                            executionId,
                            order.getExecutionId()
                    )
            );
        }

        if (order.getStatus() == OrderStatus.FILLED) {
            return;
        }

        if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
            throw new ExecutionOwnershipException(
                    String.format(
                            "Order %s already terminal (%s)",
                            order.getId(),
                            order.getStatus()
                    )
            );
        }
    }
}