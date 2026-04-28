package com.tradingbot.application.service;

import com.tradingbot.application.market.MarketDataService;
import com.tradingbot.domain.risk.RiskEngine;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Центральный компонент управления жизненным циклом торговой системы.
 * Гарантирует строгий порядок инициализации и предотвращает торговлю на неконсистентных данных.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingSystemBootstrapper {

    public enum SystemState {
        INITIALIZING,
        RISK_RECOVERING,
        RECONCILING,
        MARKET_WARMING,
        READY,
        TRADING_ENABLED,
        HALTED
    }

    private final RiskStateRecoveryService riskRecoveryService;
    private final ReconciliationService reconciliationService;
    private final MarketDataService marketDataService;
    private final RiskEngine riskEngine;

    @Getter
    private volatile SystemState state = SystemState.INITIALIZING;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        bootstrap();
    }

    public synchronized void bootstrap() {
        log.info("[BOOTSTRAP] Запуск процесса инициализации торговой системы...");
        
        try {
            // 1. Восстановление состояния рисков (Event Sourcing replay)
            updateState(SystemState.RISK_RECOVERING);
            riskRecoveryService.recover();
            log.info("[BOOTSTRAP] Состояние рисков успешно восстановлено.");

            // 2. Сверка балансов и ордеров с биржей
            updateState(SystemState.RECONCILING);
            reconciliationService.reconcileAll();
            log.info("[BOOTSTRAP] Сверка с биржей завершена.");

            // 3. Прогрев рыночных данных (загрузка свечей)
            updateState(SystemState.MARKET_WARMING);
            marketDataService.warmUpAll();
            log.info("[BOOTSTRAP] Рыночные данные прогреты.");

            // 4. Система готова к работе
            updateState(SystemState.READY);
            log.info("[BOOTSTRAP] >>> СИСТЕМА ГОТОВА К ТОРГОВЛЕ <<<");

            // 5. Активация торговых модулей
            enableTrading();

        } catch (Exception e) {
            log.error("[BOOTSTRAP] КРИТИЧЕСКАЯ ОШИБКА ПРИ ЗАПУСКЕ: {}", e.getMessage(), e);
            haltSystem("Ошибка инициализации: " + e.getMessage());
        }
    }

    private void enableTrading() {
        log.info("[BOOTSTRAP] Активация торговых модулей...");
        riskEngine.resumeTrading();
        updateState(SystemState.TRADING_ENABLED);
        log.info("[BOOTSTRAP] Торговля разрешена.");
    }

    public void haltSystem(String reason) {
        log.error("[BOOTSTRAP] АВАРИЙНАЯ ОСТАНОВКА СИСТЕМЫ: {}", reason);
        riskEngine.emergencyStop(reason);
        updateState(SystemState.HALTED);
    }

    private void updateState(SystemState newState) {
        log.info("[BOOTSTRAP] Переход состояния: {} -> {}", this.state, newState);
        this.state = newState;
    }

    public boolean isReady() {
        return state == SystemState.READY || state == SystemState.TRADING_ENABLED;
    }
}
