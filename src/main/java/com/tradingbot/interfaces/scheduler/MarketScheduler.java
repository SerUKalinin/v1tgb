package com.tradingbot.interfaces.scheduler;

import com.tradingbot.application.pipeline.TradingPipeline;
import com.tradingbot.domain.model.MarketData;
import com.tradingbot.infrastructure.client.MarketDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketScheduler {

    private final TradingPipeline pipeline;
    private final MarketDataClient marketDataClient;

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        try {
            BigDecimal price = marketDataClient.getPrice("BTCUSDT");
            MarketData data = new MarketData("BTCUSDT", price, java.time.Instant.now());
            pipeline.process(data);
        } catch (Exception e) {
            log.error("Scheduler error", e);
        }
    }
}