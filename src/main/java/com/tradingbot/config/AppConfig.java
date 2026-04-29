package com.tradingbot.config;

import com.tradingbot.application.service.TradeService;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.infrastructure.execution.binance.BinanceExecutionEngine;
import com.tradingbot.infrastructure.execution.fake.BacktestExecutionEngine;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
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
     * @param orderRepository репозиторий ордеров
     * @return экземпляр BinanceExecutionEngine
     */
    @Bean
    @Profile("prod")
    public ExecutionEngine binanceExecutionEngine(com.tradingbot.domain.port.exchange.ExecutionPort executionPort, OrderRepository orderRepository) {
        return new BinanceExecutionEngine(executionPort, orderRepository);
    }    /**
     * Создаёт исполнительный движок для бэктестирования.
     * <p>
     * Активируется в профиле "test".
     *
     * @param tradeService сервис сделок
     * @return экземпляр BacktestExecutionEngine
     */
    @Bean
    @Profile("test")
    public ExecutionEngine backtestExecutionEngine(TradeService tradeService) {
        return new BacktestExecutionEngine(tradeService);
    }
}