package com.tradingbot.interfaces.scheduler;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.service.strategy.StrategyService;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.application.event.SystemEvents;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketScheduler {

    private final MarketDataService marketDataService;
    private final StrategyService strategyService;
    private final BinanceClient binanceClient;

    private final AtomicBoolean enabled = new AtomicBoolean(false);

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1m";

    // ONLY INFRA INIT
    @PostConstruct
    public void init() {
        log.info("[SCHEDULER] Initializing market scheduler...");
        binanceClient.syncTime();
    }

    // ONLY ACTIVATION SIGNAL
    @EventListener(SystemEvents.SystemReadyEvent.class)
    public void onSystemReady(SystemEvents.SystemReadyEvent event) {
        log.info("[SCHEDULER] SystemReadyEvent received -> enabling scheduler");
        enable();
    }

    // IDENTITY GUARDED ENABLE
    public void enable() {
        if (enabled.compareAndSet(false, true)) {
            log.info("[SCHEDULER] Market scheduler ENABLED");
        }
    }

    // PURE LOOP (NO STATE COUPLING)
    @Scheduled(fixedRateString = "${trading.update-rate-ms:5000}")
    public void refreshMarketData() {
        if (!enabled.get()) return;

        try {
            marketDataService.refresh(SYMBOL, INTERVAL);
        } catch (Exception e) {
            log.error("[SCHEDULER] refresh error", e);
        }
    }

    // MAINTENANCE TASK
    @Scheduled(fixedRate = 3600000)
    public void syncServerTime() {
        if (!enabled.get()) return;
        binanceClient.syncTime();
    }
}