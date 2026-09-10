package com.tradingbot.domain.exchange;

import lombok.Value;
import java.math.BigDecimal;

/**
 * Запрос на проверку возможности исполнения сделки на бирже.
 * <p>
 * Содержит ключевые параметры ордера, необходимые для оценки
 * его допустимости: торговый символ, количество и цена.
 * Используется в рамках предварительной валидации перед отправкой
 * ордера на исполнение.
 */
@Value
public class FeasibilityRequest {

    /**
     * Торговый символ инструмента (например, BTCUSDT).
     */
    String symbol;

    /**
     * Количество базового актива в ордере.
     */
    BigDecimal quantity;

    /**
     * Цена исполнения ордера.
     */
    BigDecimal price;
}