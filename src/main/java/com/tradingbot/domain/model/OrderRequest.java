package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Запрос на исполнение торгового ордера.
 */
@Value
@Builder
public class OrderRequest {

    /**
     * Торговый символ.
     */
    String symbol;

    /**
     * Сторона ордера (покупка/продажа).
     */
    OrderSide side;

    /**
     * Количество базовой валюты.
     */
    BigDecimal amount;

    /**
     * Цена для лимитного ордера. Если null — рыночный ордер.
     */
    BigDecimal price;

    /**
     * Уникальный идентификатор ордера со стороны клиента.
     */
    String clientOrderId;
}