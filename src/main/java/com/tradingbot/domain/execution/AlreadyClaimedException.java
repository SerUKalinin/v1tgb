package com.tradingbot.domain.execution;

/**
 * Исключение, возникающее при попытке повторного захвата (claim)
 * уже обработанного executionId.
 * <p>
 * Используется для защиты от повторного выполнения одного и того же
 * ордера/сигнала в execution pipeline и обеспечения идемпотентности.
 */
public class AlreadyClaimedException extends RuntimeException {

    /**
     * Создаёт исключение с текстовым описанием причины.
     *
     * @param message описание причины возникновения ошибки
     */
    public AlreadyClaimedException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с причиной и вложенным исключением.
     *
     * @param message описание причины возникновения ошибки
     * @param cause первопричина исключения
     */
    public AlreadyClaimedException(String message, Throwable cause) {
        super(message, cause);
    }
}