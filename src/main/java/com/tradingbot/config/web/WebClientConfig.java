package com.tradingbot.config.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Конфигурация WebClient для взаимодействия с API Binance.
 */
@Configuration
public class WebClientConfig {

    /**
     * Создаёт WebClient для API Binance.
     *
     * @return экземпляр WebClient с базовым URL https://api.binance.com
     */
    @Bean
    public WebClient binanceWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.binance.com")
                .build();
    }
}