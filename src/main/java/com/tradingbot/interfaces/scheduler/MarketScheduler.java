package com.tradingbot.interfaces.scheduler;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.strategy.StrategyService;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик задач для обновления рыночных данных.
 */
import com.tradingbot.application.event.SystemEvents;
import org.springframework.context.event.EventListener;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketScheduler {

    private final MarketDataService marketDataService;
    private final StrategyService strategyService;
    private final SystemStateManager stateManager;
    private final BinanceClient binanceClient;

    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1m";

    @EventListener
    public void onSystemReady(SystemEvents.SystemReadyEvent event) {
        log.info("[SCHEDULER] System is READY. Enabling market scheduler...");
        enable();
    }

    public void enable() {
        log.info("[SCHEDULER] Market scheduler enabled.");
        this.enabled.set(true);
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
        if (!enabled.get() || !stateManager.isReady()) {
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
        if (!enabled.get()) return;
        binanceClient.syncTime();
    }
}