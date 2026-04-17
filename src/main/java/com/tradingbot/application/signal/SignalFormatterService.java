package com.tradingbot.application.signal;

import com.tradingbot.domain.model.SignalEntity;
import com.tradingbot.domain.user.SubscriptionTier;
import org.springframework.stereotype.Service;

@Service
public class SignalFormatterService {

    public String format(SignalEntity signal, SubscriptionTier tier) {
        if (tier == SubscriptionTier.PRO) {
            return formatPro(signal);
        } else {
            return formatFree(signal);
        }
    }

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
    }}
