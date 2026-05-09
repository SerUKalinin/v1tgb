package com.tradingbot.domain.execution;

public class AlreadyClaimedException extends RuntimeException {

    public AlreadyClaimedException(String message) {
        super(message);
    }

    public AlreadyClaimedException(String message, Throwable cause) {
        super(message, cause);
    }
}
