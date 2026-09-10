package com.tradingbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Конфигурация WebClient для взаимодействия с внешними API.
 *
 * <p>Основное назначение — создание HTTP-клиента для работы с биржевым API (Binance).</p>
 *
 * <p>Централизованная конфигурация позволяет:
 * <ul>
 *     <li>контролировать базовые URL</li>
 *     <li>расширять настройки (timeouts, filters, auth)</li>
 *     <li>обеспечивать единый HTTP stack для интеграций</li>
 * </ul>
 */
@Configuration
public class WebClientConfig {

    /**
     * Создаёт WebClient для Binance API.
     *
     * @return WebClient с базовым URL Binance REST API
     */
    @Bean
    public WebClient binanceWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.binance.com")
                .build();
    }
}