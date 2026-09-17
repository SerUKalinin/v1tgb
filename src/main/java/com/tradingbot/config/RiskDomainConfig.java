package com.tradingbot.config;

import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import com.tradingbot.domain.position.PositionAvailabilityPort;
import com.tradingbot.domain.risk.RiskReservationLogPort;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.tracing.ExecutionLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация доменного риск-слоя.
 *
 * <p>Явно связывает доменные компоненты с их портами.
 * Инфраструктурные зависимости не передаются в RiskService.</p>
 */
@Configuration
public class RiskDomainConfig {

    /**
     * Создаёт редьюсер риск-состояния.
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
     * @param port хранилище риск-состояния
     * @param reducer редьюсер событий
     * @param logPort лог резервирования
     * @param feasibilityPort проверка исполнимости
     * @param normalizationService нормализация ордеров
     * @param executionLogger логгер исполнения
     * @param positionAvailabilityPort доступная позиция
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
            PositionAvailabilityPort positionAvailabilityPort
    ) {
        return new RiskService(
                port,
                reducer,
                logPort,
                feasibilityPort,
                normalizationService,
                executionLogger,
                positionAvailabilityPort
        );
    }
}