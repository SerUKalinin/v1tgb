package com.tradingbot.interfaces.scheduler;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.application.service.TradingSystemBootstrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик задач для обновления рыночных данных.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketScheduler {

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1m";

    private final MarketDataService marketDataService;
    private final BinanceClient binanceClient;
    private final TradingSystemBootstrapper bootstrapper;

    private volatile boolean enabled = false;

    public void enable() {
        log.info("[SCHEDULER] Market scheduler enabled.");
        this.enabled = true;
    }

    /**
     * Инициализация: синхронизация времени.
     */
    @PostConstruct
    public void init() {
        log.info("[SCHEDULER] Initializing market scheduler...");
        binanceClient.syncTime();
    }

    /**
     * Основной цикл обновления данных.
     */
    @Scheduled(fixedRateString = "${trading.update-rate-ms:5000}")
    public void refreshMarketData() {
        if (!enabled || !bootstrapper.isReady()) {
            return;
        }
        try {
            marketDataService.refresh(SYMBOL, INTERVAL);
        } catch (Exception e) {
            log.error("[SCHEDULER] Error refreshing market data for {}", SYMBOL, e);
        }
    }

    /**
     * Синхронизация времени с сервером Binance.
     */
    @Scheduled(fixedRate = 3600000)
    public void syncServerTime() {
        if (!enabled) return;
        binanceClient.syncTime();
    }
}