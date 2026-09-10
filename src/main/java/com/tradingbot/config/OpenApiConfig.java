package com.tradingbot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация OpenAPI (Swagger) для генерации документации REST API.
 *
 * <p>Определяет базовую метаинформацию API, используемую Swagger UI и OpenAPI spec.</p>
 *
 * <p>Применяется для документирования интерфейсов торговой системы
 * (backtest / live режимы).</p>
 */
@Configuration
public class OpenApiConfig {

    /**
     * Создаёт экземпляр OpenAPI спецификации.
     *
     * @return сконфигурированный OpenAPI объект
     */
    @Bean
    public OpenAPI tradingBotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Trading Bot API")
                        .version("1.0")
                        .description("API торгового бота (backtest / live режимы)"));
    }
}