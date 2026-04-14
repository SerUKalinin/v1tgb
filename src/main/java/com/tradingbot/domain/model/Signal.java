package com.tradingbot.domain.model;

import com.tradingbot.common.enums.SignalType;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Торговый сигнал, генерируемый стратегией.
 */
@Value
public class Signal {

    /**
     * Торговый символ.
     */
    String symbol;

    /**
     * Тип сигнала (BUY, SELL, HOLD).
     */
    SignalType type;

    /**
     * Цена для исполнения сигнала.
     */
    BigDecimal price;
}