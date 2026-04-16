package com.tradingbot.application.user;

import com.tradingbot.domain.user.SubscriptionTier;
import com.tradingbot.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final com.tradingbot.infrastructure.persistence.JpaUserRepository userRepository;

    public void registerOrUpdate(Long chatId, String username) {        userRepository.findByChatId(chatId).ifPresentOrElse(
                user -> {
                    user.setUsername(username);
                    userRepository.save(user);
                },
                () -> {
                    User newUser = User.builder()
                            .chatId(chatId)
                            .username(username)
                            .tier(SubscriptionTier.FREE)
                            .active(true)
                            .build();
                    userRepository.save(newUser);
                }
        );
    }
}
