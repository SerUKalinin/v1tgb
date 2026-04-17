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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

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
        if (update == null) return;

        if (update.hasCallbackQuery()) {
            handleCallback(update);
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();

        log.info("[TG] Сообщение от {}: {}", chatId, text);

        try {
            switch (text) {
                case "/start" -> handleStart(chatId, update);
                case "/menu" -> handleMenu(chatId);
                case "/status" -> handleStatus(chatId);
                case "/subscribe" -> handleSubscribe(chatId);
                default -> sendMessage(chatId, "Неизвестная команда 🤖. Используйте /menu для навигации.");
            }
        } catch (Exception e) {
            log.error("[TG] Ошибка обработки сообщения", e);
            sendMessage(chatId, "Ошибка обработки команды ❌");
        }
    }

    private void handleCallback(Update update) {
        String data = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();

        switch (data) {
            case "stats" -> sendMessage(chatId, "📊 *Статистика* (в разработке)");
            case "signals" -> sendMessage(chatId, "📡 *Сигналы* будут приходить сюда автоматически.");
            case "subscribe" -> handleSubscribe(chatId);
            case "settings" -> sendMessage(chatId, "⚙️ *Настройки* (в разработке)");
        }
    }

    private void handleStart(Long chatId, Update update) {        String username = update.getMessage().getFrom().getUserName();
        userService.registerOrUpdate(chatId, username);

        String welcomeText = """
                🚀 *Добро пожаловать в Trading Bot SaaS!*
                
                Я — твой персональный торговый ассистент. Я генерирую сигналы, контролирую риски и помогаю тебе зарабатывать на крипторынке.
                
                💎 *Что ты получаешь:*
                • Профессиональные торговые сигналы
                • Автоматический риск-менеджмент
                • Подробную аналитику сделок
                
                🎁 Тебе доступен *FREE* тариф: сигналы приходят с задержкой и скрытыми целями.
                
                🔥 *Хочешь максимум прибыли?*
                Апгрейднись до *PRO* и получай мгновенные сигналы с точными точками входа и выхода!
                
                Используй меню ниже или команду /menu для навигации.
                """;

        sendMessage(chatId, welcomeText);
    }
    private void handleMenu(Long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(
                createButton("📊 Статистика", "stats"),
                createButton("📡 Сигналы", "signals")
        ));
        rows.add(List.of(
                createButton("💎 Купить PRO", "subscribe"),
                createButton("⚙️ Настройки", "settings")
        ));

        markup.setKeyboard(rows);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("📍 *Главное меню*")
                .parseMode("Markdown")
                .replyMarkup(markup)
                .build();

        try {
            execute(message);
        } catch (Exception e) {
            log.error("Error sending menu", e);
        }
    }

    private void handleStatus(Long chatId) {
        var user = userService.registerOrUpdate(chatId, "user");
        String statusText = String.format("""
                👤 *Ваш профиль*
                
                ID: `%d`
                Тариф: *%s*
                Статус: ✅ Активен
                """, chatId, user.getTier());
        
        sendMessage(chatId, statusText);
    }

    private void handleSubscribe(Long chatId) {
        String text = """
                💎 *Преимущества PRO подписки*
                
                • Мгновенные сигналы (без задержки)
                • Точные Take Profit и Stop Loss уровни
                • Доступ к эксклюзивным стратегиям
                • Приоритетная поддержка
                
                🔥 *Цена: $49 / месяц*
                
                Для оплаты свяжитесь с @admin или нажмите кнопку ниже (скоро).
                """;
        sendMessage(chatId, text);
    }

    private InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
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