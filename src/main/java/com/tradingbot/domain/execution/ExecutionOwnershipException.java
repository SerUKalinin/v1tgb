package com.tradingbot.domain.execution;

/**
 * Исключение, возникающее при нарушении владения execution-ресурсом.
 * <p>
 * Используется для защиты от ситуаций, когда выполнение или изменение
 * execution пытается выполнить компонент, не являющийся его владельцем.
 * Обеспечивает целостность модели исполнения в распределённой системе.
 */
public class ExecutionOwnershipException extends RuntimeException {

    /**
     * Создаёт исключение с описанием причины ошибки.
     *
     * @param message описание нарушения владения execution
     */
    public ExecutionOwnershipException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с причиной и вложенным исключением.
     *
     * @param message описание ошибки
     * @param cause первопричина исключения
     */
    public ExecutionOwnershipException(String message, Throwable cause) {
        super(message, cause);
    }
}