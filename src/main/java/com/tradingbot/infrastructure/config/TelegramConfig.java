package com.tradingbot.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
@Slf4j
@Configuration
public class TelegramConfig {

    @Bean
    public TelegramBotsApi telegramBotsApi(TradingTelegramBot tradingTelegramBot) {
        try {
            log.info("Регистрация Telegram Бота...");
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(tradingTelegramBot);
            log.info("Telegram Бот зарегистрирован!");
            return botsApi;
        } catch (TelegramApiException e) {
            log.error("НЕТ ПОДКЛЮЧЕНИЯ: Нет соединения с сервером Telegram{}. ", e.getMessage());
            return null;
        }
    }
}
