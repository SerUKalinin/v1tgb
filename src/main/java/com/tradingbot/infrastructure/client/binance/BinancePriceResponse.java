package com.tradingbot.infrastructure.client.binance;

import lombok.Data;

/**
 * Ответ Binance API с текущей ценой символа.
 */
@Data
public class BinancePriceResponse {

    /**
     * Торговый символ.
     */
    private String symbol;

    /**
     * Текущая цена.
     */
    private String price;
}