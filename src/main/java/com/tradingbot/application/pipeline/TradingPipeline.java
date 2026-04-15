package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.OrderManagementService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Торговый конвейер, объединяющий этапы обработки сигнала.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final TradingStrategy strategy;
    private final OrderManagementService orderManagementService;

    /**
     * Обрабатывает свечное окно через торговый конвейер.
     * В Stage 3 Pipeline только генерирует сигнал, а Risk Enforcement происходит внутри OMS.
     *
     * @param window окно свечей
     */
    public void process(CandleWindow window) {
        if (window.getCandles().isEmpty()) return;

        Candle lastCandle = window.getLast();
        log.info("[PIPELINE] Processing symbol={} last_close={}", window.getSymbol(), lastCandle.getClose());

        try {
            // 1. Strategy (Stateless Decision)
            Signal signal = strategy.analyze(window);
            log.info("[PIPELINE] Strategy result for {}: {}", window.getSymbol(), signal != null ? signal.getType() : "NULL");

            if (signal == null || signal.getType() == SignalType.HOLD) {
                return;
            }

            // 2. Convert to SignalEvent and delegate to OMS
            // В Stage 3 OMS + RiskManager сами решат вопрос с Sizing и Validation
            SignalEvent signalEvent = SignalEvent.builder()
                    .symbol(signal.getSymbol())
                    .type(signal.getType())
                    .price(signal.getPrice())
                    .strategyId("simple-strategy")
                    .candleTime(lastCandle.getOpenTime())
                    .build();

            log.info("[PIPELINE] Sending signal event to OMS: {}", signalEvent);
            orderManagementService.onSignal(signalEvent);

        } catch (Exception e) {
            log.error("[PIPELINE] Critical error processing {}: {}", window.getSymbol(), e.getMessage(), e);
        }
    }


            private String generateClientOrderId(CandleWindow window) {
        return String.format("%s_%s", window.getSymbol(), window.getLast().getOpenTime().toEpochMilli());
    }
}