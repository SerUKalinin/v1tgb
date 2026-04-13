package com.tradingbot.infrastructure.execution;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.execution.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Component
@Profile("backtest")
@Slf4j
public class FakeExecutionEngine implements ExecutionEngine {

    @Override
    public ExecutionResult execute(Order order) {
        BigDecimal slippage = order.getPrice().multiply(BigDecimal.valueOf(0.001));
        BigDecimal executedPrice = order.getSide() == com.tradingbot.common.enums.OrderSide.BUY
                ? order.getPrice().add(slippage)
                : order.getPrice().subtract(slippage);

        Trade trade = new Trade(order.getSymbol(), order.getSide(), order.getQuantity(), executedPrice, Instant.now());

        log.info("FAKE TRADE EXECUTED: {}", trade);
        return new ExecutionResult(true, "FAKE_" + System.currentTimeMillis(), List.of(trade));
    }
}