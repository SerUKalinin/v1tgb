package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Лог записи событий резервирования капитала в риск-системе.
 * <p>
 * Фиксирует факт изменения состояния резерва: создание или освобождение средств
 * под конкретный торговый ордер.
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
         * Тип события резервирования (RESERVE / RELEASE).
         */
        RiskReservationEventType eventType,

        /**
         * Сумма резерва.
         */
        BigDecimal amount
) {
}