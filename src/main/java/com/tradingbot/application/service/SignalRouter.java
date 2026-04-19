package com.tradingbot.application.service;

import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.model.User;
import com.tradingbot.infrastructure.telegram.TradingTelegramBot;import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalRouter {
    private final TradingTelegramBot telegramBot;
    private final UserService userService;

    public void routeSignal(Signal signal) {
        log.info("Routing signal: {}", signal);
        userService.findAllActiveUsers().forEach(user -> {
            String message = formatSignalForUser(user, signal);
            telegramBot.sendMessage(user.getChatId(), message);
        });
    }

    private String formatSignalForUser(User user, Signal signal) {
        return String.format("📢 *Сигнал: %s*\nПара: %s\nЦена: %s",
                signal.getSide(), signal.getSymbol(), signal.getPrice());
    }}