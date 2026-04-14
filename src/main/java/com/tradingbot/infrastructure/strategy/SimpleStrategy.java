package com.tradingbot.infrastructure.strategy;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import org.springframework.stereotype.Component;

@Component
public class SimpleStrategy implements TradingStrategy {
    @Override
    public Signal analyze(CandleWindow window) {
        // Простейшая логика для теста: всегда HOLD
        return new Signal(window.getSymbol(), SignalType.HOLD, window.getLast().getClose());
    }
}
