package com.tradingbot.infrastructure.strategy;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import com.tradingbot.tracing.IdentityFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Простейшая тестовая стратегия.
 * <p>
 * Всегда возвращает сигнал HOLD.
 */
@Component
@Slf4j
public class SimpleStrategy implements TradingStrategy {

    @Override
    public Signal analyze(CandleWindow window) {
        int size = window.getCandles().size();
        if (size < 2) {
            UUID signalId = IdentityFactory.derive(UUID.nameUUIDFromBytes(window.getSymbol().getBytes()), "hold-" + window.getLast().getOpenTime());
            return new Signal(signalId, window.getSymbol(), "simple-strategy", SignalType.HOLD, window.getLast().getClose(), BigDecimal.ZERO);
        }


        BigDecimal currentClose = window.getLast().getClose();
        BigDecimal prevClose = window.getCandles().get(size - 2).getClose();

        SignalType type = currentClose.compareTo(prevClose) > 0 ? SignalType.BUY : SignalType.SELL;
        BigDecimal quantity = new BigDecimal("0.001");

        log.info("[STRATEGY] Signal generated for {}: {} (current={}, prev={})", 
                window.getSymbol(), type, currentClose, prevClose);
        
        UUID signalId = IdentityFactory.derive(UUID.nameUUIDFromBytes(window.getSymbol().getBytes()), "signal-" + currentClose + "-" + quantity);
        return new Signal(signalId, window.getSymbol(), "simple-strategy", type, currentClose, quantity);
    }
}