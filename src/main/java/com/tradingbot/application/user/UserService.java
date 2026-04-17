package com.tradingbot.application.user;

import com.tradingbot.domain.user.SubscriptionTier;
import com.tradingbot.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final com.tradingbot.infrastructure.persistence.JpaUserRepository userRepository;

    public User registerOrUpdate(Long chatId, String username) {
        return userRepository.findByChatId(chatId)
                .map(user -> {
                    user.setUsername(username);
                    return userRepository.save(user);
                })
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .chatId(chatId)
                            .username(username)
                            .tier(SubscriptionTier.FREE)
                            .active(true)
                            .build();
                    return userRepository.save(newUser);
                });
    }}
