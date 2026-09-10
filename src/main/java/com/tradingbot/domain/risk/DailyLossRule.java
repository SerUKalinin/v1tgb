package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;

import java.math.BigDecimal;

/**
 * Риск-правило ограничения дневного убытка.
 * <p>
 * Запрещает открытие новых ордеров, если суммарный PnL за день
 * достиг или превысил допустимый лимит убытка.
 */
public class DailyLossRule implements RiskRule {

    /**
     * Максимально допустимый дневной убыток (отрицательное значение).
     */
    private final BigDecimal maxDailyLoss;

    /**
     * Создаёт правило ограничения дневного убытка.
     *
     * @param maxDailyLoss максимальный допустимый убыток за день
     */
    public DailyLossRule(BigDecimal maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss.abs().negate();
    }

    /**
     * Проверяет возможность исполнения ордера с точки зрения дневного риска.
     *
     * @param request входящий торговый запрос
     * @param state текущее состояние риск-движка
     * @return решение о разрешении или отклонении ордера
     */
    @Override
    public RiskDecision evaluate(OrderRequest request, RiskState state) {
        if (state.getDailyPnl().compareTo(maxDailyLoss) <= 0) {
            return RiskDecision.reject("Daily loss limit reached: " + state.getDailyPnl());
        }
        return RiskDecision.approve(request.getQuantity());
    }
}