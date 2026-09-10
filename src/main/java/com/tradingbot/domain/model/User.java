package com.tradingbot.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Доменная модель пользователя системы.
 * <p>
 * Представляет пользователя торговой системы, связанного с chatId (например, Telegram),
 * и содержит информацию об уровне подписки и статусе активности.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /**
     * Внутренний идентификатор пользователя.
     */
    private Long id;

    /**
     * Идентификатор чата (например, Telegram chatId).
     */
    private Long chatId;

    /**
     * Имя пользователя.
     */
    private String username;

    /**
     * Уровень подписки пользователя.
     */
    private SubscriptionTier tier;

    /**
     * Признак активности пользователя.
     */
    private boolean active;
}