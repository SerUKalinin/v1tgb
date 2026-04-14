package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Результат исполнения торгового ордера.
 */
@Value
public class ExecutionResult {

    /**
     * Идентификатор ордера на бирже.
     */
    String orderId;

    /**
     * Торговый символ.
     */
    String symbol;

    /**
     * Сторона ордера.
     */
    OrderSide side;

    /**
     * Исполненное количество.
     */
    BigDecimal executedQty;

    /**
     * Цена исполнения.
     */
    BigDecimal executedPrice;

    /**
     * Время исполнения.
     */
    Instant executedAt;

    /**
     * Флаг успешности исполнения.
     */
    boolean success;

    /**
     * Сообщение об ошибке.
     */
    String errorMessage;

    /**
     * Создаёт успешный результат исполнения.
     *
     * @param orderId идентификатор ордера
     * @param symbol  торговый символ
     * @param side    сторона ордера
     * @param qty     исполненное количество
     * @param price   цена исполнения
     * @return результат с success = true
     */
    public static ExecutionResult success(String orderId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price) {
        return new ExecutionResult(orderId, symbol, side, qty, price, Instant.now(), true, null);
    }

    /**
     * Создаёт результат с ошибкой.
     *
     * @param symbol       торговый символ
     * @param errorMessage сообщение об ошибке
     * @return результат с success = false
     */
    public static ExecutionResult failure(String symbol, String errorMessage) {
        return new ExecutionResult(null, symbol, null, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now(), false, errorMessage);
    }
}