package com.tradingbot.tracing;

import com.tradingbot.domain.model.Order;

import java.util.Optional;

public final class ExecutionLogFactory {

    private ExecutionLogFactory() {
    }

    public static ExecutionLogRecord from(Order order,
                                         ExecutionContext context,
                                         ExecutionEventType event,
                                         String state,
                                         String message) {
        return new ExecutionLogRecord(
                context.attempt().executionId(),
                context.business().orderId() != null && !"UNKNOWN".equals(context.business().orderId()) ? java.util.UUID.fromString(context.business().orderId()) : null,
                context.identity().signalId(),
                context.identity().correlationId(),
                context.attempt().causationId(),
                event,
                Optional.ofNullable(state).orElse(event.name()),
                message
        );
    }

    @Deprecated
    public static ExecutionLogRecord from(Order order,
                                         IdentityContext identity,
                                         ExecutionAttemptContext attempt,
                                         BusinessContext business,
                                         ExecutionEventType event,
                                         String state,
                                         String message) {
        return from(order, ExecutionContext.of(identity, attempt, business), event, state, message);
    }    @Deprecated
    public static ExecutionLogRecord from(Order order,
                                         ExecutionEventType event,
                                         String state,
                                         String message) {
        java.util.UUID executionId = resolveExecutionId(order);
        java.util.UUID orderId = order != null ? order.getId() : null;
        java.util.UUID signalId = order != null ? order.getSignalId() : null;
        return new ExecutionLogRecord(executionId, orderId, signalId, signalId, signalId, event, Optional.ofNullable(state).orElse(event.name()), message);
    }

    public static ExecutionLogRecord forSignal(java.util.UUID signalId,
                                               ExecutionEventType event,
                                               String state,
                                               String message) {
        return new ExecutionLogRecord(signalId, null, signalId, signalId, signalId, event, Optional.ofNullable(state).orElse(event.name()), message);
    }

    public static ExecutionLogRecord forEvent(java.util.UUID executionId,
                                              java.util.UUID orderId,
                                              java.util.UUID signalId,
                                              ExecutionEventType event,
                                              String state,
                                              String message) {
        return new ExecutionLogRecord(executionId, orderId, signalId, signalId, executionId, event, Optional.ofNullable(state).orElse(event.name()), message);
    }

    private static java.util.UUID resolveExecutionId(Order order) {
        if (order == null) {
            return null;
        }
        if (order.getExecutionId() != null) {
            return order.getExecutionId();
        }
        return order.getSignalId();
    }
}
