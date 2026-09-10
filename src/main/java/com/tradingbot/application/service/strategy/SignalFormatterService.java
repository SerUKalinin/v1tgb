package com.tradingbot.application.service.strategy;

import com.tradingbot.infrastructure.persistence.entity.SignalEntity;
import com.tradingbot.domain.model.SubscriptionTier;
import org.springframework.stereotype.Service;

/**
 * Сервис форматирования торговых сигналов под разные уровни подписки.
 *
 * <p>Отвечает за преобразование {@link SignalEntity} в человекочитаемый
 * текстовый формат для отправки пользователям.</p>
 *
 * <p>Поддерживает дифференциацию контента в зависимости от тарифа:
 * <ul>
 *     <li>PRO — полный набор торговых параметров</li>
 *     <li>FREE — ограниченный доступ к информации</li>
 * </ul>
 */
@Service
public class SignalFormatterService {

    /**
     * Формирует сигнал в зависимости от уровня подписки пользователя.
     *
     * @param signal торговый сигнал
     * @param tier уровень подписки пользователя
     * @return отформатированное сообщение
     */
    public String format(SignalEntity signal, SubscriptionTier tier) {
        if (tier == SubscriptionTier.PRO) {
            return formatPro(signal);
        } else {
            return formatFree(signal);
        }
    }

    /**
     * Форматирование PRO-сигнала с полным раскрытием торговых параметров.
     *
     * @param signal торговый сигнал
     * @return форматированный PRO сигнал
     */
    private String formatPro(SignalEntity signal) {
        return String.format("""
                🚀 *PRO SIGNAL: %s %s*
                
                📈 *Вход:* `%s`
                🎯 *Цель 1:* `%s`
                🎯 *Цель 2:* `%s`
                🛡 *Стоп-лосс:* `%s`
                
                📊 *Риск:* 1%% | *Плечо:* x10
                🕒 %s
                """,
                signal.getSymbol(),
                signal.getType(),
                signal.getPrice(),
                signal.getTakeProfit1(),
                signal.getTakeProfit2(),
                signal.getStopLoss(),
                signal.getTimestamp());
    }

    /**
     * Форматирование FREE-сигнала с ограничением информации.
     *
     * <p>Скрывает ключевые торговые параметры, мотивируя апгрейд.</p>
     *
     * @param signal торговый сигнал
     * @return урезанный формат сигнала
     */
    private String formatFree(SignalEntity signal) {
        return String.format("""
                📡 *FREE SIGNAL: %s %s*
                
                📈 *Вход:* `%s`
                🎯 *Цель 1:* `🔐 Скрыто в PRO`
                🎯 *Цель 2:* `🔐 Скрыто в PRO`
                🛡 *Стоп-лосс:* `🔐 Скрыто в PRO`
                
                🔥 *Хочешь видеть все цели и стопы?*
                Апгрейднись до *PRO* прямо сейчас!
                """,
                signal.getSymbol(),
                signal.getType(),
                signal.getPrice());
    }
}