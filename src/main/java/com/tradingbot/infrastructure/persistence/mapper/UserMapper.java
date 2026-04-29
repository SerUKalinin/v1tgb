package com.tradingbot.infrastructure.persistence.mapper;

import com.tradingbot.domain.model.User;
import com.tradingbot.infrastructure.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {
    public User toDomain(UserEntity entity) {
        if (entity == null) return null;
        return User.builder()
                .id(entity.getId())
                .chatId(entity.getChatId())
                .username(entity.getUsername())
                .tier(entity.getTier())
                .active(entity.isActive())
                .build();
    }

    public UserEntity toEntity(User domain) {
        if (domain == null) return null;
        return UserEntity.builder()
                .id(domain.getId())
                .chatId(domain.getChatId())
                .username(domain.getUsername())
                .tier(domain.getTier())
                .active(domain.isActive())
                .build();
    }
}
