package com.tradingbot.domain.exception;

/**
 * Исключение, выбрасываемое при некорректном состоянии ордера.
 *
 * <p>Используется для защиты state machine ордера от недопустимых переходов
 * и нарушения бизнес-инвариантов исполнения.</p>
 */
public class InvalidOrderStateException extends RuntimeException {

    /**
     * Создаёт исключение с описанием причины ошибки.
     *
     * @param message описание нарушения состояния ордера
     */
    public InvalidOrderStateException(String message) {
        super(message);
    }
}