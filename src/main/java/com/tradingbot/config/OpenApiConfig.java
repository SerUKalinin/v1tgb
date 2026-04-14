package com.tradingbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tradingBotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Trading Bot API")
                        .version("1.0")
                        .description("API для торгового бота (backtest/live)"));
    }
}