package com.tradingbot.infrastructure.execution.binance;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;

/**
 * Ответ Binance API при запросе статуса ордера.
 *
 * <p>Содержит минимальный набор данных, необходимых для:
 * <ul>
 *     <li>сопоставления состояния ордера</li>
 *     <li>синхронизации с доменной моделью</li>
 *     <li>reconciliation / recovery логики</li>
 * </ul>
 *
 * <h2>Поля:</h2>
 * <ul>
 *     <li>status — статус ордера на бирже</li>
 *     <li>executedQty — исполненное количество</li>
 *     <li>price — цена исполнения</li>
 *     <li>exchangeOrderId — идентификатор ордера на бирже</li>
 *     <li>clientOrderId — клиентский идентификатор ордера</li>
 * </ul>
 */
@Value
@Builder
public class OrderStatusResponse {

    /**
     * Статус ордера на стороне Binance (RAW).
     */
    String status;

    /**
     * Количество, которое было исполнено.
     */
    BigDecimal executedQty;

    /**
     * Цена исполнения ордера.
     */
    BigDecimal price;

    /**
     * Идентификатор ордера на бирже.
     */
    String exchangeOrderId;

    /**
     * Клиентский идентификатор ордера.
     */
    String clientOrderId;

    /**
     * Статус: ордер не найден на бирже.
     */
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";

    /**
     * Неизвестный статус ответа API.
     */
    public static final String UNKNOWN = "UNKNOWN";
}