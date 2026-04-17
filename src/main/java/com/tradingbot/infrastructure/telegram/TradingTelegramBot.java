package com.tradingbot.infrastructure.telegram;

import com.tradingbot.application.service.SubscriptionService;
import com.tradingbot.application.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@Slf4j
@RequiredArgsConstructor
public class TradingTelegramBot extends TelegramLongPollingBot {

    private final UserService userService;
    private final SubscriptionService subscriptionService;

    @Value("${telegram.bot.token}")
    private String token;

    @Value("${telegram.bot.username}")
    private String username;

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();

        log.info("[TG] Сообщение от {}: {}", chatId, text);

        try {
            switch (text) {
                case "/start" -> handleStart(chatId, update);
                default -> sendMessage(chatId, "Неизвестная команда 🤖");
            }
        } catch (Exception e) {
            log.error("[TG] Ошибка обработки сообщения", e);
            sendMessage(chatId, "Ошибка обработки команды ❌");
        }
    }

    private void handleStart(Long chatId, Update update) {
        String username = update.getMessage().getFrom().getUserName();

        userService.registerOrUpdate(chatId, username);

        sendMessage(chatId, "Бот запущен 🚀");
    }

    public void sendMessage(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();

        try {
            execute(message);
        } catch (Exception e) {
            log.error("[TG] Ошибка отправки сообщения в {}: {}", chatId, e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }
}