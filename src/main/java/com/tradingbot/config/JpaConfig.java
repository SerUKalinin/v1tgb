package com.tradingbot.config;

import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация JPA слоя.
 *
 * <p>Служит точкой расширения для настройки JPA-инфраструктуры:
 * репозиториев, entity scanning, транзакционного поведения и кастомных настроек Hibernate.</p>
 *
 * <p>На текущем этапе оставлена минимальной и может быть расширена
 * при необходимости явной конфигурации {@code @EnableJpaRepositories}.</p>
 */
@Configuration
public class JpaConfig {
    // можно добавить @EnableJpaRepositories если нужно явно
}