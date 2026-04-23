package com.tradingbot.infrastructure.outbox;

public enum OutboxStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    FAILED,
    DEAD
}
