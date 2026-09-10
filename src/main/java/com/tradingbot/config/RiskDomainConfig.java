package com.tradingbot.config;

import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import com.tradingbot.domain.risk.RiskReservationLogPort;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация доменного риск-слоя.
 *
 * <p>Отвечает за явное связывание доменных компонентов риск-менеджмента
 * с их зависимостями из портов и инфраструктурных сервисов.</p>
 *
 * <p>Фактически определяет composition root для Risk domain.</p>
 */
@Configuration
public class RiskDomainConfig {

    /**
     * Создаёт редьюсер риск-состояния.
     *
     * <p>Используется для детерминированного применения risk events
     * к текущему состоянию системы.</p>
     *
     * @return RiskStateReducer
     */
    @Bean
    public RiskStateReducer riskStateReducer() {
        return new RiskStateReducer();
    }

    /**
     * Создаёт основной RiskService.
     *
     * <p>Инкапсулирует всю бизнес-логику риск-менеджмента:
     * <ul>
     *     <li>валидацию ордеров</li>
     *     <li>резервацию капитала</li>
     *     <li>проверку feasibility через exchange слой</li>
     *     <li>логирование и outbox интеграцию</li>
     * </ul>
     *
     * @param port хранилище риск-состояния
     * @param reducer редьюсер событий
     * @param logPort лог резервирования капитала
     * @param feasibilityPort проверка ограничений биржи
     * @param normalizationService нормализация ордеров под биржу
     * @param executionLogger логгер исполнения
     * @param outboxService сервис outbox событий
     * @return RiskService
     */
    @Bean
    public RiskService riskService(
            RiskStatePort port,
            RiskStateReducer reducer,
            RiskReservationLogPort logPort,
            ExchangeFeasibilityPort feasibilityPort,
            OrderNormalizationService normalizationService,
            ExecutionLogger executionLogger,
            OutboxService outboxService
    ) {
        return new RiskService(
                port,
                reducer,
                logPort,
                feasibilityPort,
                normalizationService,
                executionLogger,
                outboxService
        );
    }
}