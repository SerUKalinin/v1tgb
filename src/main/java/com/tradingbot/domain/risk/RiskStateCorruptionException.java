package com.tradingbot.domain.risk;

public class RiskStateCorruptionException extends RuntimeException {

    public RiskStateCorruptionException(String message) {
        super(message);
    }

    public RiskStateCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
