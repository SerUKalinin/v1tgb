package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Лог записи событий резервирования капитала.
 *
 * <p>
 * Каждая запись должна иметь собственный deterministic eventId,
 * чтобы несколько CONSUME / RELEASE операций одного ордера
 * не конфликтовали в persistence.
 */
public record RiskReservationLog(

        /**
         * Идентификатор ордера.
         */
        UUID orderId,

        /**
         * Клиентский идентификатор ордера.
         */
        String clientOrderId,

        /**
         * Тип события резервирования:
         * RESERVE / RELEASE / CONSUME.
         */
        RiskReservationEventType eventType,

        /**
         * Сумма операции.
         */
        BigDecimal amount,

        /**
         * Deterministic identity конкретной risk-операции.
         *
         * <p>
         * Используется как primary key в risk_reservation_log.
         */
        UUID eventId
) {
}