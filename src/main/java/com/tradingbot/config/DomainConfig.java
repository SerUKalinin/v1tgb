package com.tradingbot.config;

import com.tradingbot.application.risk.RiskStateStore;
import com.tradingbot.domain.position.PositionReducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация доменного слоя.
 *
 * <p>Отвечает за явное создание и подключение доменных компонентов,
 * не зависящих от инфраструктуры Spring Boot автосканирования.</p>
 *
 * <p>Используется для:
 * <ul>
 *     <li>централизованной инициализации доменных сервисов</li>
 *     <li>контроля зависимостей между domain-объектами</li>
 *     <li>явного управления lifecycle критичных компонентов</li>
 * </ul>
 */
@Configuration
public class DomainConfig {

    /**
     * Хранилище состояния риск-движка.
     *
     * <p>Используется как in-memory source of truth для RiskState.</p>
     */
    @Bean
    public RiskStateStore riskStateStore() {
        return new RiskStateStore();
    }

    /**
     * Редьюсер позиций.
     *
     * <p>Отвечает за детерминированное обновление состояния позиций
     * на основе входящих событий.</p>
     */
    @Bean
    public PositionReducer positionReducer() {
        return new PositionReducer();
    }
}