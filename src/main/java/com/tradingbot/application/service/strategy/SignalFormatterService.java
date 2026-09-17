package com.tradingbot.application.service.strategy;

import com.tradingbot.domain.model.SubscriptionTier;
import org.springframework.stereotype.Service;

/**
 * Application service форматирования торговых сигналов.
 *
 * <p>
 * Работает только с application DTO и domain model.
 *
 * <p>
 * Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
public class SignalFormatterService {

    /**
     * Формирует сигнал в зависимости от уровня подписки.
     *
     * @param signal данные сигнала
     * @param tier уровень подписки
     * @return форматированное сообщение
     */
    public String format(
            SignalFormatData signal,
            SubscriptionTier tier
    ) {
        if (signal == null) {
            throw new IllegalArgumentException(
                    "signal cannot be null"
            );
        }

        if (tier == SubscriptionTier.PRO) {
            return formatPro(signal);
        }

        return formatFree(signal);
    }

    /**
     * Форматирование PRO-сигнала
     * с полным набором торговых параметров.
     */
    private String formatPro(
            SignalFormatData signal
    ) {
        return String.format(
                """
                🚀 *PRO SIGNAL: %s %s*

                📈 *Вход:* `%s`
                🎯 *Цель 1:* `%s`
                🎯 *Цель 2:* `%s`
                🛡 *Стоп-лосс:* `%s`

                📊 *Риск:* 1%% | *Плечо:* x10
                🕒 %s
                """,
                signal.symbol(),
                signal.type(),
                signal.price(),
                signal.takeProfit1(),
                signal.takeProfit2(),
                signal.stopLoss(),
                signal.timestamp()
        );
    }

    /**
     * Форматирование FREE-сигнала
     * с ограничением информации.
     */
    private String formatFree(
            SignalFormatData signal
    ) {
        return String.format(
                """
                📡 *FREE SIGNAL: %s %s*

                📈 *Вход:* `%s`
                🎯 *Цель 1:* `🔐 Скрыто в PRO`
                🎯 *Цель 2:* `🔐 Скрыто в PRO`
                🛡 *Стоп-лосс:* `🔐 Скрыто в PRO`

                🔥 *Хочешь видеть все цели и стопы?*
                Апгрейднись до *PRO* прямо сейчас!
                """,
                signal.symbol(),
                signal.type(),
                signal.price()
        );
    }
}