package com.tradingbot.tracing;

public record ExecutionLogRecord(
        String executionId,
        String orderId,
        String signalId,
        ExecutionEventType event,
        String state,
        String message
) {
}
