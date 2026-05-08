package com.tradingbot.domain.exception;

public class StaleOrderStateException extends RuntimeException {
    public StaleOrderStateException(String message) {
        super(message);
    }

    public StaleOrderStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
