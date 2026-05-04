package com.tradingbot.application.service.system;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import com.tradingbot.infrastructure.persistence.mapper.UserMapper;
import com.tradingbot.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

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
                    return userMapper.toDomain(userRepository.save(userMapper.toEntity(newUser)));
                });
    }

    public void save(User user) {
        userRepository.save(userMapper.toEntity(user));
    }
}
