package com.tradingbot.application.service.strategy;

import com.tradingbot.application.service.system.SubscriptionService;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.infrastructure.persistence.entity.SignalEntity;
import com.tradingbot.domain.model.User;
import com.tradingbot.infrastructure.persistence.mapper.UserMapper;
import com.tradingbot.infrastructure.persistence.repository.SignalRepository;
import com.tradingbot.infrastructure.persistence.repository.UserRepository;
import com.tradingbot.infrastructure.telegram.TradingTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalRouter {
    private final Optional<TradingTelegramBot> telegramBot;
    private final SubscriptionService subscriptionService;
    private final SignalRepository signalRepository;
    private final SignalFormatterService signalFormatterService;
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public void route(SignalEvent signal) {
        log.info("[ROUTER] Routing signal: {} {}", signal.getSymbol(), signal.getType());

        // Сохраняем сигнал в БД для истории
        SignalEntity entity = SignalEntity.builder()
                .symbol(signal.getSymbol())
                .type(signal.getType())
                .price(signal.getPrice())
                .takeProfit1(signal.getTakeProfit() != null ? signal.getTakeProfit() : signal.getPrice().multiply(java.math.BigDecimal.valueOf(1.0005)))
                .takeProfit2(signal.getPrice().multiply(java.math.BigDecimal.valueOf(1.0010)))
                .stopLoss(signal.getStopLoss() != null ? signal.getStopLoss() : signal.getPrice().multiply(java.math.BigDecimal.valueOf(0.9995)))                .strategyId(signal.getStrategyId())
                .createdAt(Instant.now())
                .timestamp(signal.getCandleTime())
                .build();
        
        signalRepository.save(entity);

        var users = userRepository.findAll();
        if (users.isEmpty()) {
            log.info("[ROUTER] No active users found to receive signals.");
            return;
        }

        users.forEach(userEntity -> {
            User user = userMapper.toDomain(userEntity);
            log.debug("[ROUTER] Checking user {}: active={}, tier={}", user.getChatId(), user.isActive(), user.getTier());
            if (user.isActive()) {
                String message = signalFormatterService.format(entity, user.getTier());
                log.info("[ROUTER] Sending signal to user {}: {}", user.getChatId(), message.replace("\n", " "));
                telegramBot.ifPresentOrElse(
                    bot -> bot.sendMessage(user.getChatId(), message),
                    () -> log.warn("[ROUTER] Telegram bot not available, message not sent to {}", user.getChatId())
                );
            }
        });
    }
    private String formatSignalForUser(User user, SignalEvent signal) {
        return ""; // Deprecated, using SignalFormatterService
    }
}