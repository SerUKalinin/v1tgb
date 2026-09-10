package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Доменная модель сделки (Trade).
 * <p>
 * Представляет неизменяемый факт исполнения ордера на бирже.
 * Служит источником данных для построения позиций, расчёта прибыли/убытка (PnL)
 * и аналитической обработки торговой активности.
 */
@Value
@Builder
public class Trade {

    /**
     * Внутренний идентификатор сделки.
     */
    UUID id;

    /**
     * Идентификатор сделки на стороне биржи.
     */
    String exchangeTradeId;

    /**
     * Идентификатор ордера, к которому относится сделка.
     */
    UUID orderId;

    /**
     * Клиентский идентификатор ордера.
     */
    String clientOrderId;

    /**
     * Торговый символ инструмента (например, BTCUSDT).
     */
    String symbol;

    /**
     * Идентификатор стратегии, сгенерировавшей сделку.
     */
    String strategyId;

    /**
     * Направление сделки (BUY/SELL).
     */
    OrderSide side;

    /**
     * Цена исполнения сделки.
     */
    BigDecimal price;

    /**
     * Объём исполненной сделки.
     */
    BigDecimal quantity;

    /**
     * Размер комиссии за сделку.
     */
    BigDecimal feeAmount;

    /**
     * Валюта комиссии.
     */
    String feeAsset;

    /**
     * Реализованный PnL (прибыль/убыток) по сделке.
     */
    BigDecimal realizedPnl;

    /**
     * Время исполнения сделки.
     */
    Instant executedAt;
}