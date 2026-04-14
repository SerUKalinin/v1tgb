package com.tradingbot.interfaces.scheduler;

import com.tradingbot.application.TradingPipelineService;
import com.tradingbot.domain.model.MarketData;
import com.tradingbot.infrastructure.client.binance.BinanceMarketDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MarketScheduler {

    private final BinanceMarketDataClient marketDataClient;
    private final TradingPipelineService tradingPipeline;

    @Scheduled(fixedRate = 5000)
    public void tick() {
        try {
            MarketData data = marketDataClient.getMarketData("BTCUSDT");
            tradingPipeline.process(data);
        } catch (Exception e) {
            log.error("Error in market scheduler", e);
        }
    }
}