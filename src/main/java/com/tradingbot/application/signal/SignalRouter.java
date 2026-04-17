package com.tradingbot.application.signal;

import com.tradingbot.application.service.SubscriptionService;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.SignalEntity;
import com.tradingbot.domain.user.User;
import com.tradingbot.infrastructure.persistence.SignalRepository;
import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("!test")
public class SignalRouter {

    private final com.tradingbot.infrastructure.persistence.JpaUserRepository userRepository;
    private final Optional<TradingTelegramBot> telegramBot;
    private final SubscriptionService subscriptionService;
    private final SignalRepository signalRepository;

    public void route(SignalEvent signal) {
        log.info("[ROUTER] Routing signal: {} {}", signal.getSymbol(), signal.getType());

        // Сохраняем сигнал в БД для истории
        signalRepository.save(SignalEntity.builder()
                .symbol(signal.getSymbol())
                .type(signal.getType())
                .price(signal.getPrice())
                .strategyId(signal.getStrategyId())
                .createdAt(Instant.now())
                .build());

        var users = userRepository.findAll();
        if (!users.iterator().hasNext()) {
            log.info("[ROUTER] No active users found to receive signals.");
            return;
        }

        users.forEach(user -> {
            if (user.isActive()) {
                String message = formatSignalForUser(user, signal);
                log.info("[ROUTER] Sending signal to user {}: {}", user.getChatId(), message.replace("\n", " "));
                telegramBot.ifPresentOrElse(
                    bot -> bot.sendMessage(user.getChatId(), message),
                    () -> log.warn("[ROUTER] Telegram bot not available, message not sent to {}", user.getChatId())
                );
            }
        });
    }

    private String formatSignalForUser(User user, SignalEvent signal) {
        if (subscriptionService.canAccessFullSignal(user)) {
            return String.format(
                    "💎 [PRO] НОВЫЙ СИГНАЛ!\n\n" +
                    "Инструмент: %s\n" +
                    "Тип: %s\n" +
                    "Цена: %s\n" +
                    "Время: %s",
                    signal.getSymbol(),
                    signal.getType(),
                    signal.getPrice(),
                    signal.getCandleTime()
            );
        } else {
            return String.format(
                    "🔔 [FREE] ОБНАРУЖЕН СИГНАЛ!\n\n" +
                    "Инструмент: %s\n" +
                    "Тип: %s\n" +
                    "Цена: [СКРЫТО 🔒]\n\n" +
                    "Чтобы видеть цену в реальном времени, активируйте PRO подписку!",
                    signal.getSymbol(),
                    signal.getType()
            );
        }
    }
}