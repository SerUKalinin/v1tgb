package com.tradingbot.application.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;

/**
 * Набор системных событий жизненного цикла торговой системы.
 * <p>
 * Используется для координации стадий bootstrap-процесса:
 * cold start, reconciliation, готовность системы к торговле.
 * <p>
 * События публикуются через Spring ApplicationEventPublisher
 * и используются различными компонентами системы для реакции на изменения состояния.
 */
public class SystemEvents {

    /**
     * Событие, сигнализирующее о cold start сценарии.
     * <p>
     * Возникает, когда обнаружен баланс на бирже при отсутствии внутреннего состояния системы.
     * Используется для запуска процедуры первичной синхронизации.
     */
    @Getter
    @RequiredArgsConstructor
    public static class ColdStartDetectedEvent {

        /**
         * Баланс, полученный с биржи на момент обнаружения cold start.
         */
        private final BigDecimal exchangeBalance;
    }

    /**
     * Событие запроса стандартной процедуры reconciliation.
     * <p>
     * Возникает при расхождении внутреннего и внешнего состояния баланса.
     * Используется для синхронизации состояния системы с биржей.
     */
    @Getter
    @RequiredArgsConstructor
    public static class StandardReconciliationRequestedEvent {

        /**
         * Внутренний баланс системы.
         */
        private final BigDecimal internalBalance;

        /**
         * Баланс, полученный с биржи.
         */
        private final BigDecimal exchangeBalance;
    }

    /**
     * Событие, сигнализирующее о полной готовности системы к торговле.
     * <p>
     * Используется для активации торговых компонентов (scheduler, execution pipeline, strategy engine).
     */
    public static class SystemReadyEvent {}
}