package com.tradingbot.application.bootstrap;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.risk.RiskStateRecoveryService;
import com.tradingbot.application.event.SystemEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSystemBootstrapper {

    private final SystemStateManager stateManager;
    private final RiskStateRecoveryService riskRecoveryService;
    private final MarketDataService marketDataService;
    private final RiskEngine riskEngine;
    private final com.tradingbot.domain.execution.ExchangeOrderQueryService exchangeQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        bootstrap();
    }

    public synchronized void bootstrap() {

        log.info("[BOOTSTRAP] START");

        try {
            // 1. RISK RECOVERY
            stateManager.updateState(SystemStateManager.SystemState.RISK_RECOVERING);
            riskRecoveryService.recover();
            log.info("[BOOTSTRAP] risk recovered");

            // 2. BALANCE CHECK
            BigDecimal internal = riskEngine.getState().availableBalance();
            BigDecimal exchange = exchangeQueryService.getAvailableBalance("USDT");

            if (internal.signum() == 0 && exchange.signum() > 0) {
                stateManager.updateState(SystemStateManager.SystemState.COLD_START_RECONCILIATION);
            } else {
                stateManager.updateState(SystemStateManager.SystemState.RECONCILING);
            }

            // 3. MARKET WARMUP
            stateManager.updateState(SystemStateManager.SystemState.MARKET_WARMING);
            marketDataService.warmUpAll();

            // 4. READY
            stateManager.updateState(SystemStateManager.SystemState.READY);

            log.info("[BOOTSTRAP] READY");

            // 5. CRITICAL: SINGLE ACTIVATION SIGNAL
            stateManager.updateState(SystemStateManager.SystemState.TRADING_ENABLED);

            eventPublisher.publishEvent(new SystemEvents.SystemReadyEvent());

        } catch (Exception e) {
            haltSystem(e.getMessage());
        }
    }

    public void haltSystem(String reason) {
        log.error("[BOOTSTRAP] HALT: {}", reason);
        riskEngine.emergencyStop(reason);
        stateManager.updateState(SystemStateManager.SystemState.HALTED);
    }
}