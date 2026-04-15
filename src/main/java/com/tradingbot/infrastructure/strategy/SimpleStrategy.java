package com.tradingbot.infrastructure.strategy;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Простейшая тестовая стратегия.
 * <p>
 * Всегда возвращает сигнал HOLD.
 */
@Component
public class SimpleStrategy implements TradingStrategy {

    /**
     * Анализирует окно свечей и возвращает сигнал HOLD.
     *
     * @param window окно свечей
     * @return сигнал HOLD
     */
    @Override
    public Signal analyze(CandleWindow window) {
        // Простейшая логика: всегда HOLD для примера
        return new Signal(window.getSymbol(), "simple-strategy", SignalType.HOLD, window.getLast().getClose(), BigDecimal.ZERO);
    }
}