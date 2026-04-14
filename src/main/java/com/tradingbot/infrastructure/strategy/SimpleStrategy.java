package com.tradingbot.infrastructure.strategy;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.MarketData;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import org.springframework.stereotype.Component;

@Component
public class SimpleStrategy implements TradingStrategy {
    @Override
    public Signal generateSignal(MarketData data) {
        // Простейшая логика для теста
        return new Signal(data.symbol(), SignalType.HOLD, data.price());
    }
}
