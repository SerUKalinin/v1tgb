package com.tradingbot.application.service.strategy;

import com.tradingbot.application.service.system.SubscriptionService;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.SignalPort;
import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import com.tradingbot.domain.model.UserPort;
import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Роутер торговых сигналов.
 *
 * <p>
 * Application layer работает только через domain ports.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalRouter {

    private final Optional<TradingTelegramBot> telegramBot;

    /**
     * Оставляем зависимость, чтобы не менять текущий wiring,
     * пока не проверена её дальнейшая необходимость.
     */
    private final SubscriptionService subscriptionService;

    private final SignalPort signalPort;
    private final UserPort userPort;
    private final SignalFormatterService signalFormatterService;

    @EventListener
    public void route(SignalEvent signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal cannot be null");
        }

        log.info(
                "[ROUTER] Routing signal: {} {}",
                signal.getSymbol(),
                signal.getType()
        );

        /*
         * Persistence полностью скрыта за SignalPort.
         */
        signalPort.save(signal);

        List<User> users = userPort.findAll();

        if (users.isEmpty()) {
            log.info("[ROUTER] No active users found to receive signals.");
            return;
        }

        SignalFormatData formatData = new SignalFormatData(
                signal.getSymbol(),
                signal.getType(),
                signal.getPrice(),
                resolveTakeProfit1(signal),
                resolveTakeProfit2(signal),
                resolveStopLoss(signal),
                signal.getCandleTime()
        );

        users.forEach(user -> routeToUser(user, formatData));
    }

    private void routeToUser(
            User user,
            SignalFormatData signal
    ) {
        if (user == null || !user.isActive()) {
            return;
        }

        SubscriptionTier tier = user.getTier();

        log.debug(
                "[ROUTER] Checking user {}: active={}, tier={}",
                user.getChatId(),
                user.isActive(),
                tier
        );

        String message =
                signalFormatterService.format(signal, tier);

        log.info(
                "[ROUTER] Sending signal to user {}: {}",
                user.getChatId(),
                message.replace("\n", " ")
        );

        telegramBot.ifPresentOrElse(
                bot -> bot.sendMessage(user.getChatId(), message),
                () -> log.warn(
                        "[ROUTER] Telegram bot not available, message not sent to {}",
                        user.getChatId()
                )
        );
    }

    private BigDecimal resolveTakeProfit1(SignalEvent signal) {
        return signal.getTakeProfit() != null
                ? signal.getTakeProfit()
                : signal.getPrice()
                  .multiply(BigDecimal.valueOf(1.0005));
    }

    private BigDecimal resolveTakeProfit2(SignalEvent signal) {
        return signal.getPrice()
                .multiply(BigDecimal.valueOf(1.0010));
    }

    private BigDecimal resolveStopLoss(SignalEvent signal) {
        return signal.getStopLoss() != null
                ? signal.getStopLoss()
                : signal.getPrice()
                  .multiply(BigDecimal.valueOf(0.9995));
    }
}