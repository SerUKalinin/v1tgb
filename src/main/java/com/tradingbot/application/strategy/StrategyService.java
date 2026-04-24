package com.tradingbot.application.strategy;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Сервис для управления торговыми стратегиями.
 * Слушает события закрытых свечей и генерирует торговые сигналы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onNewCandle(NewClosedCandleEvent event) {
        log.info("[STRATEGY] Processing new candle for symbol: {}", event.symbol());
        
        // Временная тестовая логика: генерируем BUY сигнал на каждую закрытую свечу
        // В будущем здесь будет вызов конкретных реализаций стратегий
        generateTestSignal(event);
    }

    private void generateTestSignal(NewClosedCandleEvent candle) {
        SignalEvent signal = SignalEvent.builder()
                .symbol(candle.symbol())
                .strategyId("SMA_CROSS_STUB")
                .type(SignalType.BUY)
                .price(candle.close())
                .candleTime(candle.closeTime())
                .build();

        log.info("[STRATEGY] Generated test signal: {} {} at {}", 
                signal.getSymbol(), signal.getType(), signal.getPrice());
        
        eventPublisher.publishEvent(signal);
    }
}
