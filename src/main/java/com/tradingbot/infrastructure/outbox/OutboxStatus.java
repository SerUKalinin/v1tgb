package com.tradingbot.infrastructure.outbox;

/**
 * Статусы жизненного цикла outbox-события.
 *
 * <p>Используются для управления обработкой событий в {@link OutboxProcessor}:
 * <ul>
 *     <li>NEW — событие создано и ожидает обработки</li>
 *     <li>PROCESSING — событие захвачено и находится в обработке</li>
 *     <li>PROCESSED — событие успешно обработано</li>
 *     <li>FAILED — временная ошибка, допускается повторная обработка (retry)</li>
 *     <li>DEAD — превышено число попыток, событие отправлено в DLQ</li>
 * </ul>
 *
 * <p>Статусы формируют конечный автомат обработки outbox-событий и используются
 * для обеспечения надежной доставки и идемпотентности.
 */
public enum OutboxStatus {
    NEW,
    PROCESSING,
    PROCESSED,
    FAILED,
    DEAD
}