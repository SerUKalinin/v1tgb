package com.tradingbot.application.service.system;

import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Сервис административных уведомлений.
 *
 * <p>Отвечает за отправку критических и риск-событий
 * администратору системы через Telegram.</p>
 *
 * <p>Используется как канал экстренной сигнализации для:
 * <ul>
 *     <li>ошибок уровня системы</li>
 *     <li>событий риск-менеджмента</li>
 *     <li>критических нарушений инвариантов</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminNotificationService {

    /**
     * Telegram бот (ленивая инициализация для избежания циклических зависимостей).
     */
    private final @Lazy TradingTelegramBot telegramBot;

    /**
     * ID чата администратора.
     */
    @Value("${telegram.bot.admin-id}")
    private Long adminChatId;

    /**
     * Отправляет критическое уведомление об ошибке.
     *
     * @param context контекст возникновения ошибки
     * @param errorMessage сообщение об ошибке
     */
    public void notifyCriticalError(String context, String errorMessage) {
        String alertMessage = String.format(
                "🚨 *CRITICAL ERROR ALERT*\n\n" +
                        "📍 *Context:* %s\n" +
                        "❌ *Error:* %s\n\n" +
                        "⚠️ Требуется немедленное вмешательство!",
                context, errorMessage
        );

        log.error("[ALERT] Sending critical notification to admin: {}", errorMessage);
        telegramBot.sendMessage(adminChatId, alertMessage);
    }

    /**
     * Отправляет уведомление о событии риск-менеджмента.
     *
     * @param message описание события риска
     */
    public void notifyRiskEvent(String message) {
        String alertMessage = "🛡 *RISK EVENT*\n\n" + message;
        telegramBot.sendMessage(adminChatId, alertMessage);
    }

    /**
     * Упрощённый метод отправки критического уведомления.
     *
     * @param message текст сообщения
     */
    public void sendCritical(String message) {
        notifyCriticalError("SYSTEM", message);
    }
}