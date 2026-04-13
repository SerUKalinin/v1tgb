package com.tradingbot.application.pipeline;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.execution.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.application.service.PositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradingPipeline {

    private final TradingStrategy strategy;
    private final RiskManager riskManager;
    private final ExecutionEngine executionEngine;
    private final PositionService positionService;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public void process(com.tradingbot.domain.model.MarketData data) {
        log.info("Market data: {}", data);

        var signal = strategy.generateSignal(data);
        if (signal.getType() == com.tradingbot.common.enums.SignalType.HOLD) return;

        var decision = riskManager.evaluate(signal, null);
        if (!decision.isApproved()) {
            log.warn("Risk rejected: {}", decision.getReason());
            return;
        }

        Order order = mapToOrder(signal);
        OrderEntity entity = orderMapper.toEntity(order);
        orderRepository.save(entity);

        ExecutionResult result = executionEngine.execute(order);

        for (Trade trade : result.getTrades()) {
            positionService.applyTrade(trade);
        }

        log.info("Execution result: {}", result);
    }

    private Order mapToOrder(com.tradingbot.domain.model.TradingSignal signal) {
        return new Order(
                signal.getSymbol(),
                signal.getType() == com.tradingbot.common.enums.SignalType.BUY ? com.tradingbot.common.enums.OrderSide.BUY : com.tradingbot.common.enums.OrderSide.SELL,
                BigDecimal.valueOf(0.001),
                signal.getPrice()
        );
    }
}