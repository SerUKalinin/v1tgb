package com.tradingbot.infrastructure.execution.binance;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Ответ Binance API при запросе состояния ордера.
 *
 * <p>Содержит данные, необходимые для reconciliation/recovery:</p>
 *
 * <ul>
 *     <li>raw Binance status</li>
 *     <li>executed quantity</li>
 *     <li>order price</li>
 *     <li>cumulative quote quantity</li>
 *     <li>exchange order id</li>
 *     <li>client order id</li>
 * </ul>
 */
@Value
@Builder
public class OrderStatusResponse {

    /**
     * Raw статус Binance.
     */
    String status;

    /**
     * Фактически исполненное количество.
     */
    BigDecimal executedQty;

    /**
     * Цена ордера.
     *
     * <p>Для MARKET-order не следует использовать её
     * как authoritative average fill price.</p>
     */
    BigDecimal price;

    /**
     * Кумулятивная quote-value всех исполнений.
     *
     * <p>Используется для расчёта средней цены:
     * cumulativeQuoteQty / executedQty.</p>
     */
    BigDecimal cummulativeQuoteQty;

    /**
     * Идентификатор ордера на бирже.
     */
    String exchangeOrderId;

    /**
     * Клиентский идентификатор ордера.
     */
    String clientOrderId;

    public static final String ORDER_NOT_FOUND =
            "ORDER_NOT_FOUND";

    public static final String UNKNOWN =
            "UNKNOWN";
}