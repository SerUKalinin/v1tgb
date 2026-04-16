package com.tradingbot.infrastructure.telegram;

import com.tradingbot.application.user.UserService;
import com.tradingbot.application.service.SubscriptionService;
import com.tradingbot.domain.user.SubscriptionTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradingTelegramBot extends TelegramLongPollingBot {

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    private final UserService userService;
    private final com.tradingbot.infrastructure.persistence.JpaUserRepository userRepository;
    private final SubscriptionService subscriptionService;
    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        log.debug("Received update from Telegram: {}", update);
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String username = update.getMessage().getFrom().getUserName();
            
            log.info("Message from {}: {}", username, messageText);

            if (messageText.equals("/start")) {
                userService.registerOrUpdate(chatId, username);
                sendTextMessage(chatId, "Добро пожаловать в Trading Bot! 🚀\nВы зарегистрированы как FREE пользователь.");
            } else if (messageText.equals("/status")) {
                userRepository.findByChatId(chatId).ifPresentOrElse(
                    user -> sendTextMessage(chatId, subscriptionService.getSubscriptionStatus(user)),
                    () -> sendTextMessage(chatId, "Вы не зарегистрированы. Нажмите /start")
                );
            } else if (messageText.equals("/activate_pro_test")) {
                userRepository.findByChatId(chatId).ifPresent(user -> {
                    user.setTier(SubscriptionTier.PRO);
                    userRepository.save(user);
                    sendTextMessage(chatId, "✅ Тестовая PRO подписка активирована!");
                });
            }        }
    }

    public void sendTextMessage(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Error sending message to {}: {}", chatId, e.getMessage());
        }
    }
}
