package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.PositionService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Торговый конвейер, объединяющий этапы обработки сигнала.
 * <p>
 * Последовательность: стратегия → риск-менеджер → исполнение → обновление позиции.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final TradingStrategy strategy;
    private final RiskManager riskManager;
    private final ExecutionEngine executionEngine;
    private final PositionService positionService;

    /**
     * Обрабатывает свечное окно через торговый конвейер.
     *
     * @param window окно свечей
     */
    public void process(CandleWindow window) {
        if (window.getCandles().isEmpty()) return;

        Candle lastCandle = window.getLast();
        log.info("[PIPELINE] Processing symbol={} last_close={}", window.getSymbol(), lastCandle.getClose());

        // 1. Strategy
        Signal signal = strategy.analyze(window);
        if (signal == null || signal.getType() == SignalType.HOLD) {
            return;
        }

        // 2. Risk
        RiskDecision decision = riskManager.evaluate(signal);
        if (!decision.isApproved()) {
            log.warn("[PIPELINE] Risk rejected: {}", decision.getReason());
            return;
        }

        // 3. Execution
        OrderRequest request = OrderRequest.builder()
                .symbol(signal.getSymbol())
                .side(signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL)
                .amount(decision.getAmount())
                .price(signal.getPrice())
                .clientOrderId(UUID.randomUUID().toString())
                .build();
        ExecutionResult result = executionEngine.execute(request);

        // 4. Position Update
        if (result.isSuccess()) {
            log.info("[PIPELINE] Trade executed: orderId={}", result.getOrderId());
            positionService.applyExecution(result);
        } else {
            log.error("[PIPELINE] Execution failed: {}", result.getErrorMessage());
        }
    }
}