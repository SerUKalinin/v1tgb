package com.tradingbot.application.service.system;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import com.tradingbot.infrastructure.persistence.mapper.UserMapper;
import com.tradingbot.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Сервис управления пользователями.
 *
 * <p>Отвечает за:
 * <ul>
 *     <li>регистрацию новых пользователей</li>
 *     <li>обновление существующих пользователей</li>
 *     <li>синхронизацию domain ↔ persistence моделей</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /**
     * Репозиторий пользователей.
     */
    private final UserRepository userRepository;

    /**
     * Маппер domain ↔ entity.
     */
    private final UserMapper userMapper;

    /**
     * Регистрирует нового пользователя или обновляет существующего.
     *
     * <p>Поведение:
     * <ul>
     *     <li>если пользователь существует — обновляется username</li>
     *     <li>если не существует — создаётся с тарифом FREE</li>
     * </ul>
     *
     * @param chatId идентификатор Telegram чата
     * @param username имя пользователя
     * @return доменная модель пользователя
     */
    public User registerOrUpdate(Long chatId, String username) {
        return userRepository.findByChatId(chatId)
                .map(entity -> {
                    entity.setUsername(username);
                    return userMapper.toDomain(userRepository.save(entity));
                })
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .chatId(chatId)
                            .username(username)
                            .tier(SubscriptionTier.FREE)
                            .active(true)
                            .build();

                    return userMapper.toDomain(
                            userRepository.save(userMapper.toEntity(newUser))
                    );
                });
    }

    /**
     * Сохраняет доменную модель пользователя в базу данных.
     *
     * @param user пользователь доменного слоя
     */
    public void save(User user) {
        userRepository.save(userMapper.toEntity(user));
    }
}