package com.tradingbot.infrastructure.client.binance;

import lombok.Data;

@Data
public class BinancePriceResponse {

    private String symbol;
    private String price;
}