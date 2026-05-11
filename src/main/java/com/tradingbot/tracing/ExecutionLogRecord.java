package com.tradingbot.tracing;

public record ExecutionLogRecord(
        java.util.UUID executionId,
        java.util.UUID orderId,
        java.util.UUID signalId,
        ExecutionEventType event,
        String state,
        String message
) {
}
