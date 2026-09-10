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

/**
 * Роутер торговых сигналов.
 *
 * <p>Отвечает за полный pipeline обработки сигнала:
 * <ul>
 *     <li>персистирование сигнала в БД</li>
 *     <li>получение списка пользователей</li>
 *     <li>проверку активности и подписки</li>
 *     <li>формирование сообщения под тариф</li>
 *     <li>доставку через Telegram bot</li>
 * </ul>
 *
 * <p>Является точкой интеграции стратегии с пользовательским слоем.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalRouter {

    /**
     * Telegram бот (опциональный, может быть отключен в окружении).
     */
    private final Optional<TradingTelegramBot> telegramBot;

    /**
     * Сервис подписок пользователей.
     */
    private final SubscriptionService subscriptionService;

    /**
     * Репозиторий сигналов.
     */
    private final SignalRepository signalRepository;

    /**
     * Сервис форматирования сигналов.
     */
    private final SignalFormatterService signalFormatterService;

    /**
     * Репозиторий пользователей.
     */
    private final UserRepository userRepository;

    /**
     * Маппер user entity → domain.
     */
    private final UserMapper userMapper;

    /**
     * Основной метод маршрутизации сигнала.
     *
     * <p>Сохраняет сигнал и рассылает его всем активным пользователям.</p>
     *
     * @param signal доменное событие сигнала стратегии
     */
    public void route(SignalEvent signal) {
        log.info("[ROUTER] Routing signal: {} {}", signal.getSymbol(), signal.getType());

        // Сохранение сигнала в БД (история и аналитика)
        SignalEntity entity = SignalEntity.builder()
                .symbol(signal.getSymbol())
                .type(signal.getType())
                .price(signal.getPrice())
                .takeProfit1(signal.getTakeProfit() != null
                        ? signal.getTakeProfit()
                        : signal.getPrice().multiply(java.math.BigDecimal.valueOf(1.0005)))
                .takeProfit2(signal.getPrice().multiply(java.math.BigDecimal.valueOf(1.0010)))
                .stopLoss(signal.getStopLoss() != null
                        ? signal.getStopLoss()
                        : signal.getPrice().multiply(java.math.BigDecimal.valueOf(0.9995)))
                .strategyId(signal.getStrategyId())
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

            log.debug("[ROUTER] Checking user {}: active={}, tier={}",
                    user.getChatId(), user.isActive(), user.getTier());

            if (user.isActive()) {
                String message = signalFormatterService.format(entity, user.getTier());

                log.info("[ROUTER] Sending signal to user {}: {}",
                        user.getChatId(),
                        message.replace("\n", " "));

                telegramBot.ifPresentOrElse(
                        bot -> bot.sendMessage(user.getChatId(), message),
                        () -> log.warn("[ROUTER] Telegram bot not available, message not sent to {}",
                                user.getChatId())
                );
            }
        });
    }

    /**
     * Legacy-заглушка (устаревший форматтер сигналов).
     *
     * @deprecated используется {@link SignalFormatterService}
     */
    @Deprecated
    private String formatSignalForUser(User user, SignalEvent signal) {
        return "";
    }
}