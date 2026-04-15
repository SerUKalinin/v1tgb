package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.OrderManagementService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.model.Candle;
import com.tradingbot.domain.model.CandleWindow;
import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Торговый конвейер, объединяющий этапы обработки сигнала.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final TradingStrategy strategy;
    private final RiskManager riskManager;
    private final OrderManagementService orderManagementService;

    /**
     * Обрабатывает свечное окно через торговый конвейер.
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

            // 2. Risk (Stateless Decision)
            RiskDecision decision = riskManager.evaluate(signal);
            log.info("[PIPELINE] Risk decision for {}: approved={}, reason={}, amount={}", 
                    signal.getType(), decision.isApproved(), decision.getReason(), decision.getAmount());
            
            if (!decision.isApproved()) {
                log.warn("[PIPELINE] Risk rejected: {}", decision.getReason());
                return;
            }

            // 3. Prepare Order Request (DTO)
            OrderRequest request = OrderRequest.builder()
                    .symbol(signal.getSymbol())
                    .side(signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL)
                    .type(OrderType.MARKET)
                    .amount(decision.getAmount())
                    .price(signal.getPrice())
                    .strategyId("simple-strategy")
                    .clientOrderId(generateClientOrderId(window))
                    .build();
            // 4. Delegate to OMS (Stateful Transactional Boundary)
            log.info("[PIPELINE] Sending order to OMS: {}", request.getClientOrderId());
            orderManagementService.executeOrder(request);
        } catch (Exception e) {
            log.error("[PIPELINE] Critical error processing {}: {}", window.getSymbol(), e.getMessage(), e);
        }
    }    private String generateClientOrderId(CandleWindow window) {
        return String.format("%s_%s", window.getSymbol(), window.getLast().getOpenTime().toEpochMilli());
    }
}