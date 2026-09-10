package com.tradingbot.domain.risk;

/**
 * Порт для записи логов резервирования капитала.
 * <p>
 * Используется для аудита и трассировки всех операций RESERVE/RELEASE
 * в рамках риск-менеджмента.
 */
public interface RiskReservationLogPort {

    /**
     * Добавляет запись о событии резервирования капитала.
     *
     * @param log доменная запись лога резервирования
     */
    void append(RiskReservationLog log);
}