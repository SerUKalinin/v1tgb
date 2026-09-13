package com.tradingbot.application.service.system;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.model.User;
import org.springframework.stereotype.Service;

/**
 * Сервис управления подписками пользователей.
 *
 * <p>Определяет уровень доступа пользователя к функциональности системы,
 * в частности к деталям торговых сигналов.</p>
 *
 * <p>Используется для контроля монетизации и ограничения контента.</p>
 */
@Service
public class SubscriptionService {

    /**
     * Проверяет доступ пользователя к полным деталям торгового сигнала.
     *
     * <p>Правила доступа:
     * <ul>
     *     <li>PRO — полный доступ</li>
     *     <li>VIP — полный доступ</li>
     *     <li>FREE — ограниченный доступ</li>
     * </ul>
     *
     * @param user пользователь системы
     * @return true если доступ к полному сигналу разрешён
     */
    public boolean canAccessFullSignal(User user) {
        return user.getTier() == SubscriptionTier.PRO ||
                user.getTier() == SubscriptionTier.VIP;
    }

    /**
     * Возвращает строковое представление статуса подписки пользователя.
     *
     * @param user пользователь системы
     * @return форматированное сообщение со статусом подписки
     */
    public String getSubscriptionStatus(User user) {
        return String.format("Ваш уровень подписки: *%s*", user.getTier());
    }
}