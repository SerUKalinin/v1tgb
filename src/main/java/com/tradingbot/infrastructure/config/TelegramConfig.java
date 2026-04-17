package com.tradingbot.infrastructure.config;

import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class TelegramConfig {

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
