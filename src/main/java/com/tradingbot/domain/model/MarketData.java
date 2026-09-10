package com.tradingbot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Текущие рыночные данные (тикер).
 * <p>
 * Представляет актуальную цену торгового инструмента на момент времени.
 * Используется в стратегиях, индикаторах и системах принятия торговых решений.
 *
 * @param symbol    торговый символ инструмента
 * @param price     текущая рыночная цена
 * @param timestamp временная метка фиксации значения
 */
public record MarketData(
        String symbol,
        BigDecimal price,
        Instant timestamp
) {}