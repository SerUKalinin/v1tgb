package com.tradingbot.config.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    @Bean
    public WebClient binanceWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.binance.com")
                .build();
    }
}