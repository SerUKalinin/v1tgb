package com.tradingbot.interfaces.scheduler;

import com.tradingbot.application.service.TradingPipelineService;
import com.tradingbot.domain.model.MarketData;
import com.tradingbot.infrastructure.client.MarketDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketScheduler {

    private final TradingPipelineService pipeline;
    private final MarketDataClient marketDataClient;

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        try {
            BigDecimal price = marketDataClient.getPrice("BTCUSDT");

            if (price == null) return;

            MarketData data = new MarketData(
                    "BTCUSDT",
                    price,
                    Instant.now()
            );

            pipeline.process(data);

        } catch (Exception e) {
            log.error("Scheduler error", e);
        }
    }
}