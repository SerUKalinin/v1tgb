package com.tradingbot.domain.risk;

/**
 * Исключение, выбрасываемое при обнаружении повреждения состояния risk-engine.
 *
 * <p>Используется, когда RiskState нарушает финансовые или структурные инварианты,
 * либо восстановлен в неконсистентном виде из хранилища.</p>
 */
public class RiskStateCorruptionException extends RuntimeException {

    public RiskStateCorruptionException(String message) {
        super(message);
    }

    public RiskStateCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}