package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Торговая позиция по символу.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    /**
     * Торговый символ.
     */
    private String symbol;

    /**
     * Количество базовой валюты в позиции.
     */
    private BigDecimal quantity;

    /**
     * Средняя цена входа в позицию.
     */
    private BigDecimal entryPrice;
}