package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.domain.model.SubscriptionTier;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "chat_id", unique = true, nullable = false)
    private Long chatId;
    
    @Column(name = "username")
    private String username;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false)
    private SubscriptionTier tier;

    @Column(name = "active", nullable = false)
    private boolean active;
}
