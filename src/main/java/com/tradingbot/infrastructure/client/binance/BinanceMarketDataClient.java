package com.tradingbot.infrastructure.client.binance;

import com.tradingbot.domain.model.MarketData;
import com.tradingbot.infrastructure.client.MarketDataClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class BinanceMarketDataClient implements MarketDataClient {

    private final WebClient binanceWebClient;

    @Override
    public BigDecimal getPrice(String symbol) {
        return getMarketData(symbol).price();
    }

    @Override
    public MarketData getMarketData(String symbol) {
        BinancePriceResponse response = fetch(symbol);

        return new MarketData(
                symbol,
                new BigDecimal(response.getPrice()),
                java.time.Instant.now()
        );
    }

    private BinancePriceResponse fetch(String symbol) {
        return binanceWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/ticker/price")
                        .queryParam("symbol", symbol)
                        .build())
                .retrieve()
                .bodyToMono(BinancePriceResponse.class)
                .block();
    }
}