package com.tradingbot.domain.subscription;

/**
 * Уровень подписки пользователя.
 *
 * <p>Определяет доступные возможности системы:
 * <ul>
 *   <li>FREE — базовый доступ</li>
 *   <li>PRO — расширенный функционал</li>
 *   <li>VIP — полный доступ к системе</li>
 * </ul>
 * </p>
 */
public enum SubscriptionLevel {
    FREE,
    PRO,
    VIP
}