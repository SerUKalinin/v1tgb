package com.tradingbot.domain.model;

import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Текущие рыночные данные (тикер).
 *
 * @param symbol    торговый символ
 * @param price     текущая цена
 * @param timestamp временная метка
 */
public record MarketData(
        String symbol,
        BigDecimal price,
        Instant timestamp
) {}