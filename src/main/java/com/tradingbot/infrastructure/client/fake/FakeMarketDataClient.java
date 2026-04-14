package com.tradingbot.infrastructure.client.fake;

import com.tradingbot.domain.model.MarketData;
import com.tradingbot.infrastructure.client.MarketDataClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Profile("backtest")
public class FakeMarketDataClient implements MarketDataClient {

    @Override
    public BigDecimal getPrice(String symbol) {
        return getMarketData(symbol).price();
    }

    @Override
    public MarketData getMarketData(String symbol) {
        // Генерируем случайную цену в районе 60000 для BTC
        double randomPrice = 60000 + ThreadLocalRandom.current().nextDouble(-100, 100);
        return new MarketData(
                symbol,
                BigDecimal.valueOf(randomPrice),
                Instant.now()
        );
    }
}
