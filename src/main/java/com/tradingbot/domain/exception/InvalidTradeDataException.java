package com.tradingbot.domain.exception;

import java.math.BigDecimal;

/**
 * Исключение, возникающее при некорректных данных сделки,
 * передаваемых в PositionEntity.applyTrade().
 *
 * <p>Используется для защиты доменных инвариантов позиции
 * от неконсистентных или повреждённых входных данных.</p>
 */
public class InvalidTradeDataException extends RuntimeException {

    /**
     * Создаёт исключение при обнаружении невалидного поля сделки.
     *
     * @param symbol торговый символ позиции
     * @param field имя некорректного поля
     * @param value значение поля (может быть null)
     */
    public InvalidTradeDataException(String symbol, String field, BigDecimal value) {
        super(String.format(
                "Invalid trade data for position %s: %s cannot be %s",
                symbol,
                field,
                value == null ? "null" : value.toPlainString()
        ));
    }
}