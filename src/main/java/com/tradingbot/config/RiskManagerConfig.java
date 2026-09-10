package com.tradingbot.config;

import com.tradingbot.application.risk.DefaultRiskManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.tracing.ExecutionLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация RiskManager слоя.
 *
 * <p>Отвечает за создание и связывание реализации {@link RiskManager}
 * с бизнес-логикой риск-менеджмента и движком исполнения.</p>
 *
 * <p>Является composition root для orchestration слоя risk pipeline.</p>
 */
@Configuration
public class RiskManagerConfig {

    /**
     * Создаёт основной RiskManager системы.
     *
     * <p>RiskManager координирует:
     * <ul>
     *     <li>RiskService (бизнес-правила)</li>
     *     <li>RiskEngine (runtime состояние и ограничения)</li>
     *     <li>ExecutionLogger (audit trail)</li>
     * </ul>
     *
     * @param riskService доменный сервис риск-менеджмента
     * @param riskEngine движок риск-состояния
     * @param executionLogger логгер исполнения
     * @return реализация RiskManager
     */
    @Bean
    public RiskManager riskManager(
            RiskService riskService,
            RiskEngine riskEngine,
            ExecutionLogger executionLogger
    ) {
        return new DefaultRiskManager(
                riskService,
                riskEngine,
                executionLogger
        );
    }
}