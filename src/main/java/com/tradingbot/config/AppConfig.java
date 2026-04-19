package com.tradingbot.config;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.infrastructure.execution.binance.BinanceExecutionEngine;
import com.tradingbot.infrastructure.execution.fake.BacktestExecutionEngine;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.infrastructure.persistence.repository.ExecutionIdempotencyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Конфигурация приложения.
 * <p>
 * Определяет бины исполнительного движка в зависимости от профиля Spring.
 */
@Configuration
public class AppConfig {

    /**
     * Создаёт исполнительный движок для реальной торговли на Binance.
     * <p>
     * Активируется в профиле "prod".
     *
     * @param binanceClient клиент API Binance
     * @param idempotencyRepository репозиторий для идемпотентности
     * @return экземпляр BinanceExecutionEngine
     */
    @Bean
    @Profile("prod")
    public ExecutionEngine binanceExecutionEngine(
            BinanceClient binanceClient,
            ExecutionIdempotencyRepository idempotencyRepository) {
        return new BinanceExecutionEngine(binanceClient, idempotencyRepository);
    }
    /**
     * Создаёт исполнительный движок для бэктестирования.
     * <p>
     * Активируется в профиле "test".
     *
     * @param eventPublisher издатель событий
     * @return экземпляр BacktestExecutionEngine
     */
    @Bean
    @Profile("test")
    public ExecutionEngine backtestExecutionEngine(ApplicationEventPublisher eventPublisher) {
        return new BacktestExecutionEngine(eventPublisher);
    }
}