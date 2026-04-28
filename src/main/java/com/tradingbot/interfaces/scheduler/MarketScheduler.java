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

    /**
     * Инициализация: синхронизация времени.
     * Прогрев кэша теперь управляется через TradingSystemBootstrapper.
     */
    @PostConstruct
    public void init() {
        log.info("[SCHEDULER] Initializing market scheduler...");
        binanceClient.syncTime();
    }

    /**
     * Основной цикл обновления данных.
     * Вызывается с фиксированной задержкой.
     * Блокируется до завершения инициализации системы.
     */
    @Scheduled(fixedRateString = "${trading.update-rate-ms:5000}")
    public void refreshMarketData() {
        if (!bootstrapper.isReady()) {
            log.debug("[SCHEDULER] System not ready, skipping market refresh");
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
     * Вызывается раз в час.
     */
    @Scheduled(fixedRate = 3600000)
    public void syncServerTime() {
        binanceClient.syncTime();
    }
}