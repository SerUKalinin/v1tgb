package com.tradingbot.infrastructure.client;

import com.tradingbot.domain.model.MarketData;
import java.math.BigDecimal;

/**
 * Клиент для получения рыночных данных.
 */
public interface MarketDataClient {

    /**
     * Возвращает текущую цену указанного символа.
     *
     * @param symbol торговый символ
     * @return текущая цена
     */
    BigDecimal getPrice(String symbol);

    /**
     * Возвращает текущие рыночные данные указанного символа.
     *
     * @param symbol торговый символ
     * @return рыночные данные
     */
    MarketData getMarketData(String symbol);
}