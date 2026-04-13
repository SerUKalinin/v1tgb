package com.tradingbot.infrastructure.client.binance;

import com.tradingbot.domain.model.MarketData;
import com.tradingbot.infrastructure.client.MarketDataClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class BinanceMarketDataClient implements MarketDataClient {

    private final WebClient binanceWebClient;

    @Override
    public BigDecimal getPrice(String symbol) {
        BinancePriceResponse response = binanceWebClient.get()
                .uri("/api/v3/ticker/price?symbol={symbol}", symbol)
                .retrieve()
                .bodyToMono(BinancePriceResponse.class)
                .block();
        return new BigDecimal(response.getPrice());
    }

    @Override
    public MarketData getMarketData(String symbol) {
        return new MarketData(symbol, getPrice(symbol), Instant.now());
    }
}

