package com.tradingbot.infrastructure.config;

import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация Telegram-бота.
 *
 * <p>Отвечает за инициализацию и регистрацию Telegram-бота
 * в режиме Long Polling через TelegramBotsApi.
 *
 * <p>Активна во всех профилях, кроме {@code test}.
 */
@Slf4j
@Configuration
@Profile("!test")
public class TelegramConfig {

    /**
     * Регистрирует Telegram-бота в Telegram API и запускает Long Polling сессию.
     *
     * @param bot экземпляр торгового Telegram-бота
     * @return TelegramBotsApi или {@code null} в случае критической ошибки инициализации
     */
    @Bean
    public TelegramBotsApi telegramBotsApi(TradingTelegramBot bot) {
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(bot);
            log.info("[TG] Бот успешно зарегистрирован в режиме Long Polling");
            return api;
        } catch (Exception e) {
            log.error("[TG] Критическая ошибка при регистрации бота: {}. Проверьте интернет-соединение или VPN.", e.getMessage());
            return null;
        }
    }
}