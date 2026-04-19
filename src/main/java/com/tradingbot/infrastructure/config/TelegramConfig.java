package com.tradingbot.infrastructure.config;

import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Telegram Bot конфигурация.
 *
 * ПАТЧ: добавлен @ConditionalOnProperty — бот регистрируется только если
 * telegram.bot.enabled=true (default: true).
 * Для локальной разработки без сети: telegram.bot.enabled=false
 * → приложение стартует без попытки подключиться к Telegram API.
 *
 * Проблема оригинала: TelegramBotsApi() при регистрации немедленно коннектится к
 * api.telegram.org. Без VPN / без сети → ConnectException на старте.
 * return null при ошибке в оригинале → NullPointerException при первом использовании бота.
 * Правильное решение: не создавать бин вообще, а не возвращать null.
 */
@Slf4j
@Configuration
public class TelegramConfig {

    @Bean
    @ConditionalOnProperty(
            name  = "telegram.bot.enabled",
            havingValue = "true",
            matchIfMissing = true  // по умолчанию включено (продакшен)
    )
    public TelegramBotsApi telegramBotsApi(TradingTelegramBot bot) throws Exception {
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(bot);
            log.info("[TG] Бот зарегистрирован: Long Polling активен");
            return api;
        } catch (Exception e) {
            // Логируем как WARN, не ERROR — в локальной среде это ожидаемо
            log.warn("[TG] Не удалось зарегистрировать бота: {}. " +
                    "Установите telegram.bot.enabled=false для локального запуска.", e.getMessage());
            // Пробрасываем исключение — пусть Spring решает (не глотаем молча)
            throw e;
        }
    }
}