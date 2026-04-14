package com.tradingbot.infrastructure.risk;

import com.tradingbot.domain.model.RiskDecision;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DefaultRiskManager implements RiskManager {
    @Override
    public RiskDecision evaluate(Signal signal) {
        // Базовая логика: разрешаем всё с фиксированным объемом
        return RiskDecision.approved(BigDecimal.valueOf(0.01));
    }
}
