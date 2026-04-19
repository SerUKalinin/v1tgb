package com.tradingbot.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * AGGREGATE ROOT — Telegram-пользователь.
 *
 * Является владельцем orders, positions, trades.
 * chatId — внешний ключ Telegram API (бизнес-уникален).
 */
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_active", columnList = "active")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Telegram Chat ID — бизнес-уникальный идентификатор. */
    @Column(name = "chat_id", nullable = false, unique = true)
    private Long chatId;

    @Column(name = "username")
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    @Builder.Default
    private SubscriptionTier tier = SubscriptionTier.FREE;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}