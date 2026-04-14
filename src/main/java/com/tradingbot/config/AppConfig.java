package com.tradingbot.config;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.infrastructure.execution.binance.BinanceExecutionEngine;
import com.tradingbot.infrastructure.execution.fake.BacktestExecutionEngine;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class AppConfig {
    @Bean
    @Profile("prod")
    public ExecutionEngine binanceExecutionEngine(BinanceClient binanceClient) {
        return new BinanceExecutionEngine(binanceClient);
    }

    @Bean
    @Profile("test")
    public ExecutionEngine backtestExecutionEngine() {
        return new BacktestExecutionEngine();
    }
}