package com.tradingbot.domain.exception;

import java.math.BigDecimal;

/**
 * Выбрасывается, когда PositionEntity.applyTrade() получает
 * некорректные данные сделки (null-поля или невалидные значения).
 */
public class InvalidTradeDataException extends RuntimeException {
    public InvalidTradeDataException(String symbol, String field, BigDecimal value) {
        super(String.format(
                "Invalid trade data for position %s: %s cannot be %s",
                symbol, field, value == null ? "null" : value.toPlainString()
        ));
    }
}