package com.tradingbot.domain.execution;

public class ExecutionOwnershipException extends RuntimeException {
    public ExecutionOwnershipException(String message) {
        super(message);
    }

    public ExecutionOwnershipException(String message, Throwable cause) {
        super(message, cause);
    }
}
