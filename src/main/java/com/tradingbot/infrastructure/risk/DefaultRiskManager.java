package com.tradingbot.infrastructure.risk;

import com.tradingbot.domain.model.RiskDecision;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Базовая реализация риск-менеджера.
 * <p>
 * Всегда одобряет сигналы с фиксированным объёмом 0.01.
 */
@Component
public class DefaultRiskManager implements RiskManager {

    /**
     * Оценивает сигнал и возвращает решение.
     *
     * @param signal торговый сигнал
     * @return решение с фиксированным объёмом 0.01
     */
    @Override
    public RiskDecision evaluate(Signal signal) {
        return RiskDecision.approved(BigDecimal.valueOf(0.01));
    }
}