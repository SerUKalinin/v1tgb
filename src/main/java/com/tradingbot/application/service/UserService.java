package com.tradingbot.application.service;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import com.tradingbot.infrastructure.persistence.repository.JpaUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final JpaUserRepository userRepository;

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
