package com.tradingbot.config;

import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.infrastructure.execution.binance.BinanceExecutionEngine;
import com.tradingbot.infrastructure.execution.fake.BacktestExecutionEngine;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Конфигурация приложения.
 *
 * <p>Определяет реализации {@link ExecutionEngine} в зависимости от активного Spring profile.</p>
 *
 * <p>Фактически управляет выбором execution backend:
 * <ul>
 *     <li>prod → реальная торговля через Binance</li>
 *     <li>test → backtest / симуляция исполнения</li>
 * </ul>
 */
@Configuration
public class AppConfig {

    /**
     * Бин исполнительного движка для production среды (Binance).
     *
     * <p>Используется для реальной торговли и отправки ордеров на биржу.</p>
     *
     * @param executionPort порт взаимодействия с биржей
     * @param orderRepository репозиторий ордеров
     * @return реализация ExecutionEngine для Binance
     */
    @Bean
    @Profile("prod")
    public ExecutionEngine binanceExecutionEngine(
            ExecutionPort executionPort,
            OrderRepository orderRepository
    ) {
        return new BinanceExecutionEngine(executionPort, orderRepository);
    }

    /**
     * Бин исполнительного движка для тестовой среды.
     *
     * <p>Используется для backtesting и локальной симуляции исполнения ордеров.</p>
     *
     * @param tradeService сервис работы со сделками
     * @return реализация ExecutionEngine для backtest
     */
    @Bean
    @Profile("test")
    public ExecutionEngine backtestExecutionEngine(TradeService tradeService) {
        return new BacktestExecutionEngine(tradeService);
    }
}