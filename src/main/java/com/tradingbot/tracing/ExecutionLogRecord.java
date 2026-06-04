package com.tradingbot.tracing;

public record ExecutionLogRecord(
        java.util.UUID executionId,
        java.util.UUID orderId,
        java.util.UUID signalId,
        java.util.UUID correlationId,
        java.util.UUID causationId,
        ExecutionEventType eventType,
        String state,
        String message
) {
}
