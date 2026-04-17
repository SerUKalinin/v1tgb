package com.tradingbot.application.service;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionService {

    /**
     * Проверяет, может ли пользователь видеть полные детали сигнала.
     * Для MVP: PRO и VIP видят всё, FREE видят ограниченную информацию.
     */
    public boolean canAccessFullSignal(User user) {
        return user.getTier() == SubscriptionTier.PRO || 
               user.getTier() == SubscriptionTier.VIP;
    }

    public String getSubscriptionStatus(User user) {
        return String.format("Ваш уровень подписки: *%s*", user.getTier());
    }
}
