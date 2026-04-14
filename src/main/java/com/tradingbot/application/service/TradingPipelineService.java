package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.execution.ExecutionResult;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class TradingPipelineService {

    private final TradingStrategy strategy;
    private final RiskManager riskManager;
    private final ExecutionEngine executionEngine;
    private final PositionService positionService;

    public TradingPipelineService(
            @Qualifier("simpleStrategy") TradingStrategy strategy,
            RiskManager riskManager,
            ExecutionEngine executionEngine,
            PositionService positionService
    ) {
        this.strategy = strategy;
        this.riskManager = riskManager;
        this.executionEngine = executionEngine;
        this.positionService = positionService;
    }

    public void process(MarketData data) {

        String symbol = data.symbol();
        BigDecimal price = data.price();

        log.info("[PIPELINE] symbol={} price={}", symbol, price);

        TradingSignal signal = strategy.analyze(symbol, price);

        if (signal.type() == SignalType.HOLD) {
            log.debug("[PIPELINE] HOLD signal");
            return;
        }

        boolean approved = riskManager.approve(signal, price);

        if (!approved) {
            log.warn("[PIPELINE] rejected signal={}", signal.type());
            return;
        }

        Order order = new Order(
                symbol,
                signal.type() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL,
                BigDecimal.ONE,
                price
        );

        ExecutionResult result = executionEngine.execute(order);

        if (!result.isSuccess()) {
            log.error("[PIPELINE] execution failed orderId={}", result.getOrderId());
            return;
        }

        if (result.getTrades() != null) {
            result.getTrades().forEach(positionService::applyTrade);
        }
    }
}