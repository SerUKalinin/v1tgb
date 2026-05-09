package com.tradingbot.tracing;

import com.tradingbot.domain.model.Order;

import java.util.Optional;

public final class ExecutionLogFactory {

    private ExecutionLogFactory() {
    }

    public static ExecutionLogRecord from(Order order,
                                         ExecutionEventType event,
                                         String state,
                                         String message) {
        String executionId = resolveExecutionId(order);
        String orderId = order != null ? order.getId().toString() : null;
        String signalId = order != null ? order.getSignalId() : null;
        return new ExecutionLogRecord(executionId, orderId, signalId, event, Optional.ofNullable(state).orElse(event.name()), message);
    }

    public static ExecutionLogRecord forSignal(String signalId,
                                               ExecutionEventType event,
                                               String state,
                                               String message) {
        return new ExecutionLogRecord(signalId, null, signalId, event, Optional.ofNullable(state).orElse(event.name()), message);
    }

    public static ExecutionLogRecord forEvent(String executionId,
                                              String orderId,
                                              String signalId,
                                              ExecutionEventType event,
                                              String state,
                                              String message) {
        return new ExecutionLogRecord(executionId, orderId, signalId, event, Optional.ofNullable(state).orElse(event.name()), message);
    }

    private static String resolveExecutionId(Order order) {
        if (order == null) {
            return null;
        }
        if (order.getExecutionId() != null) {
            return order.getExecutionId().toString();
        }
        return order.getSignalId();
    }
}
