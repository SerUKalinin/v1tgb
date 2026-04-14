package com.tradingbot.application;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipelineService {

    private final TradingStrategy strategy;
    private final RiskManager riskManager;
    private final ExecutionEngine executionEngine;
    private final PositionService positionService;

    public void process(MarketData data) {
        log.info("[PIPELINE] Processing symbol={} price={}", data.symbol(), data.price());

        // 1. Strategy
        Signal signal = strategy.generateSignal(data);
        if (signal.getType() == SignalType.HOLD) {
            return;
        }

        // 2. Risk
        RiskDecision decision = riskManager.evaluate(signal);
        if (!decision.isApproved()) {
            log.warn("[PIPELINE] Risk rejected: {}", decision.getReason());
            return;
        }

        // 3. Execution
        OrderRequest request = new OrderRequest(
                signal.getSymbol(),
                signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL,
                decision.getAmount(),
                signal.getPrice()
        );

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
