package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.User;
import com.tradingbot.domain.model.UserPort;
import com.tradingbot.infrastructure.persistence.entity.UserEntity;
import com.tradingbot.infrastructure.persistence.mapper.UserMapper;
import com.tradingbot.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA implementation of UserPort.
 *
 * <p>
 * Все детали persistence остаются внутри infrastructure.
 */
@Component
@RequiredArgsConstructor
public class JpaUserAdapter implements UserPort {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByChatId(Long chatId) {
        return userRepository.findByChatId(chatId)
                .map(userMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll()
                .stream()
                .map(userMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user cannot be null");
        }

        UserEntity entity = userMapper.toEntity(user);

        if (entity == null) {
            throw new IllegalStateException("UserMapper returned null entity");
        }

        UserEntity saved = userRepository.save(entity);
        return userMapper.toDomain(saved);
    }
}