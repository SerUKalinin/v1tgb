package com.tradingbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация OpenAPI (Swagger) для генерации документации API.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Создаёт и настраивает объект OpenAPI.
     *
     * @return экземпляр OpenAPI с метаинформацией
     */
    @Bean
    public OpenAPI tradingBotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Trading Bot API")
                        .version("1.0")
                        .description("API для торгового бота (backtest/live)"));
    }
}