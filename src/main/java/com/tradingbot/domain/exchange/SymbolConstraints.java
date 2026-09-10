package com.tradingbot.domain.exchange;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;

/**
 * Ограничения торгового инструмента (символа), полученные с биржи.
 * <p>
 * Используются для валидации и нормализации ордеров перед отправкой
 * на исполнение. Определяют допустимые шаги цены и количества,
 * а также минимальные ограничения по объёму сделки.
 */
@Value
@Builder
public class SymbolConstraints {

    /**
     * Торговый символ инструмента (например, BTCUSDT).
     */
    String symbol;

    /**
     * Шаг изменения количества (LOT_SIZE.stepSize).
     */
    BigDecimal stepSize;

    /**
     * Минимально допустимое количество (LOT_SIZE.minQty).
     */
    BigDecimal minQty;

    /**
     * Минимальный шаг изменения цены (PRICE_FILTER.tickSize).
     */
    BigDecimal tickSize;

    /**
     * Минимальная сумма ордера (NOTIONAL.minNotional).
     */
    BigDecimal minNotional;
}