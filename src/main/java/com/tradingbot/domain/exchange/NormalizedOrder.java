package com.tradingbot.domain.exchange;

import lombok.Value;
import java.math.BigDecimal;

/**
 * Нормализованная модель ордера, приведённая к единому формату
 * внутри доменного слоя.
 * <p>
 * Используется после этапов парсинга/валидации/нормализации входных данных
 * перед передачей в execution pipeline или адаптер биржи.
 */
@Value
public class NormalizedOrder {

    /**
     * Торговый символ инструмента (например, BTCUSDT).
     */
    String symbol;

    /**
     * Количество базового актива.
     */
    BigDecimal quantity;

    /**
     * Цена исполнения ордера.
     */
    BigDecimal price;
}