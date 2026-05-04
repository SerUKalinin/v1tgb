package com.tradingbot.application.bootstrap;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.application.service.risk.RiskStateRecoveryService;
import com.tradingbot.domain.risk.RiskEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Центральный компонент управления жизненным циклом торговой системы.
 * Гарантирует строгий порядок инициализации и предотвращает торговлю на неконсистентных данных.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSystemBootstrapper {

    private final SystemStateManager stateManager;
    private final RiskStateRecoveryService riskRecoveryService;
    private final MarketDataService marketDataService;
    private final RiskEngine riskEngine;
    private final com.tradingbot.domain.execution.ExchangeOrderQueryService exchangeQueryService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        bootstrap();
    }

    public synchronized void bootstrap() {
        log.info("[BOOTSTRAP] Запуск процесса инициализации торговой системы...");
        
        try {
            // 1. Восстановление состояния рисков
            stateManager.updateState(SystemStateManager.SystemState.RISK_RECOVERING);
            riskRecoveryService.recover();
            log.info("[BOOTSTRAP] Состояние рисков успешно восстановлено.");

            // 2. Определение режима сверки и публикация события
            BigDecimal internalBalance = riskEngine.getState().availableBalance();
            BigDecimal exchangeBalance = exchangeQueryService.getAvailableBalance("USDT");

            if (!stateManager.isReady() && internalBalance.signum() == 0 && exchangeBalance.signum() > 0) {
                log.info("[BOOTSTRAP] Обнаружен холодный старт (Internal=0, Exchange={}).", exchangeBalance);
                stateManager.updateState(SystemStateManager.SystemState.COLD_START_RECONCILIATION);
                eventPublisher.publishEvent(new com.tradingbot.application.event.SystemEvents.ColdStartDetectedEvent(exchangeBalance));
            } else {
                log.info("[BOOTSTRAP] Стандартная сверка (Internal={}, Exchange={}).", internalBalance, exchangeBalance);
                stateManager.updateState(SystemStateManager.SystemState.RECONCILING);
                eventPublisher.publishEvent(new com.tradingbot.application.event.SystemEvents.StandardReconciliationRequestedEvent(internalBalance, exchangeBalance));
            }
            
            // 3. Прогрев данных
            stateManager.updateState(SystemStateManager.SystemState.MARKET_WARMING);
            marketDataService.warmUpAll();
            log.info("[BOOTSTRAP] Рыночные данные прогреты.");

            // 4. Система готова
            stateManager.updateState(SystemStateManager.SystemState.READY);
            log.info("[BOOTSTRAP] >>> СИСТЕМА ГОТОВА К ТОРГОВЛЕ <<<");

            // 5. Публикация события готовности
            eventPublisher.publishEvent(new com.tradingbot.application.event.SystemEvents.SystemReadyEvent());
            
            // 6. Активация торговли
            enableTrading();

        } catch (Exception e) {
            log.error("[BOOTSTRAP] КРИТИЧЕСКАЯ ОШИБКА ПРИ ЗАПУСКЕ: {}", e.getMessage(), e);
            haltSystem("Ошибка инициализации: " + e.getMessage());
        }
    }

    private void enableTrading() {
        if (stateManager.getState() != SystemStateManager.SystemState.READY) {
            return;
        }
        log.info("[BOOTSTRAP] Активация торговых модулей...");
        riskEngine.resumeTrading();
        stateManager.updateState(SystemStateManager.SystemState.TRADING_ENABLED);
        log.info("[BOOTSTRAP] >>> ТОРГОВЛЯ РАЗРЕШЕНА И ЗАПУЩЕНА <<<");
    }

    public void haltSystem(String reason) {
        log.error("[BOOTSTRAP] АВАРИЙНАЯ ОСТАНОВКА СИСТЕМЫ: {}", reason);
        riskEngine.emergencyStop(reason);
        stateManager.updateState(SystemStateManager.SystemState.HALTED);
    }
}
