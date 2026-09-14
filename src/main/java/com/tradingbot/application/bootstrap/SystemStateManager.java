package com.tradingbot.application.bootstrap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Менеджер состояния системы.
 *
 * Отвечает за управление жизненным циклом торговой системы:
 * от инициализации до режима реальной торговли.
 *
 * Состояние используется как глобальный флаг готовности системы
 * для запуска стратегий, риск-логики и execution pipeline.
 */
@Slf4j
@Component
public class SystemStateManager {

    /**
     * Состояния жизненного цикла системы.
     */
    public enum SystemState {

        /**
         * Начальная стадия запуска системы.
         */
        INITIALIZING,

        /**
         * Восстановление риск-состояния после старта.
         */
        RISK_RECOVERING,

        /**
         * Режим холодного старта с полной сверкой состояния.
         */
        COLD_START_RECONCILIATION,

        /**
         * Сверка состояния системы с внешними источниками.
         */
        RECONCILING,

        /**
         * Прогрев рыночных данных и стратегий.
         */
        MARKET_WARMING,

        /**
         * Система полностью готова к работе.
         */
        READY,

        /**
         * Активный режим торговли.
         */
        TRADING_ENABLED,

        /**
         * Аварийная остановка системы.
         */
        HALTED
    }

    /**
     * Текущее состояние системы.
     *
     * volatile используется для обеспечения видимости
     * состояния между потоками без дополнительной синхронизации.
     */
    @Getter
    private volatile SystemState state = SystemState.INITIALIZING;

    /**
     * Обновляет текущее состояние системы.
     *
     * @param newState новое состояние, в которое переходит система
     */
    public void updateState(SystemState newState) {
        log.info("[SYSTEM-STATE] Transition: {} -> {}", this.state, newState);
        this.state = newState;
    }

    /**
     * Проверяет, готова ли система к торговым операциям.
     *
     * @return true если система находится в состоянии READY или TRADING_ENABLED
     */
    public boolean isReady() {
        return state == SystemState.READY || state == SystemState.TRADING_ENABLED;
    }

    /**
     * Проверяет, находится ли система в режиме холодного старта.
     *
     * @return true если система выполняет cold start reconciliation
     */
    public boolean isColdStart() {
        return state == SystemState.COLD_START_RECONCILIATION;
    }
}