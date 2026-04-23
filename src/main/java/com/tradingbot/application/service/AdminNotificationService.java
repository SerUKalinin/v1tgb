package com.tradingbot.application.service;

import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminNotificationService {

    private final @Lazy TradingTelegramBot telegramBot;
    @Value("${telegram.bot.admin-id}")
    private Long adminChatId;

    /**
     * Отправляет критическое уведомление администратору.
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
     * Отправляет уведомление о срабатывании риск-менеджмента.
     */
    public void notifyRiskEvent(String message) {
        String alertMessage = "🛡 *RISK EVENT*\n\n" + message;
        telegramBot.sendMessage(adminChatId, alertMessage);
    }

    public void sendCritical(String message) {
        notifyCriticalError("SYSTEM", message);
    }
}
