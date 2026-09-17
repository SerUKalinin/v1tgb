package com.tradingbot.application.service.system;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import com.tradingbot.domain.model.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Application service управления пользователями.
 *
 * <p>
 * Persistence полностью скрыт за UserPort.
 *
 * <p>
 * Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserPort userPort;

    /**
     * Регистрирует нового пользователя
     * или обновляет существующего.
     *
     * <p>
     * Поведение:
     * <ul>
     *     <li>существующий пользователь получает новый username;</li>
     *     <li>новый пользователь создаётся с тарифом FREE;</li>
     *     <li>новый пользователь активен.</li>
     * </ul>
     *
     * @param chatId идентификатор Telegram chat
     * @param username имя пользователя
     * @return сохранённая доменная модель пользователя
     */
    public User registerOrUpdate(
            Long chatId,
            String username
    ) {
        if (chatId == null) {
            throw new IllegalArgumentException(
                    "chatId cannot be null"
            );
        }

        return userPort
                .findByChatId(chatId)
                .map(existing -> {
                    existing.setUsername(username);
                    return userPort.save(existing);
                })
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .chatId(chatId)
                            .username(username)
                            .tier(SubscriptionTier.FREE)
                            .active(true)
                            .build();

                    return userPort.save(newUser);
                });
    }

    /**
     * Сохраняет пользователя.
     *
     * @param user доменная модель пользователя
     */
    public void save(User user) {
        userPort.save(user);
    }
}