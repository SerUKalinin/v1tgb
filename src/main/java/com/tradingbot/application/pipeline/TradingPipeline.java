package com.tradingbot.application.pipeline;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final List<TradingStrategy> strategies;
    private final ApplicationEventPublisher eventPublisher;

    public void process(CandleWindow window) {
        if (window == null || window.getCandles().isEmpty()) {
            return;
        }

        log.info("[PIPELINE] Processing symbol={} candles={}", 
                window.getSymbol(), 
                window.getCandles().size());

        for (TradingStrategy strategy : strategies) {
            Signal signal = strategy.analyze(window);
            SignalType signalType = signal.getType();

            if (signalType == SignalType.HOLD) {
                continue;
            }

            SignalEvent event = SignalEvent.builder()
                    .symbol(window.getSymbol())
                    .strategyId(signal.getStrategyId())
                    .type(signalType)
                    .price(signal.getPrice())
                    .candleTime(window.getLast().getOpenTime())
                    .build();

            log.info("[PIPELINE] Emit signal: {} {} from {}",
                    event.getSymbol(),
                    event.getType(),
                    event.getStrategyId());

            eventPublisher.publishEvent(event);
        }
    }}
