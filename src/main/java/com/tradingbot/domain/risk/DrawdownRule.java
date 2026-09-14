package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.OrderRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Риск-правило ограничения максимальной просадки (drawdown).
 * <p>
 * Отслеживает пиковую equity и блокирует новые сделки при превышении
 * заданного процента просадки от исторического максимума.
 */
public class DrawdownRule implements RiskRule {

    /**
     * Максимально допустимая просадка в процентах.
     */
    private final BigDecimal maxDrawdownPercent;

    /**
     * Исторический максимум equity (peak).
     */
    private BigDecimal peakEquity = BigDecimal.ZERO;

    /**
     * Создаёт правило ограничения drawdown.
     *
     * @param maxDrawdownPercent максимальная допустимая просадка в процентах
     */
    public DrawdownRule(BigDecimal maxDrawdownPercent) {
        this.maxDrawdownPercent = maxDrawdownPercent;
    }

    /**
     * Оценивает торговый запрос с точки зрения риска просадки.
     *
     * @param request входящий торговый запрос
     * @param state текущее состояние риск-системы
     * @return решение о разрешении или отклонении ордера
     */
    @Override
    public RiskDecision evaluate(OrderRequest request, RiskState state) {
        BigDecimal currentEquity = state.getTotalEquity();

        if (currentEquity.compareTo(peakEquity) > 0) {
            peakEquity = currentEquity;
        }

        if (peakEquity.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drawdown = peakEquity.subtract(currentEquity)
                    .divide(peakEquity, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (drawdown.compareTo(maxDrawdownPercent) >= 0) {
                return RiskDecision.reject("Max drawdown reached: " + drawdown + "%");
            }
        }

        return RiskDecision.approve(request.getQuantity());
    }
}