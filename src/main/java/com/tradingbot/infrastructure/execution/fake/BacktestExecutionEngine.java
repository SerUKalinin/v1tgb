package com.tradingbot.infrastructure.execution.fake;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("backtest")
public class BacktestExecutionEngine implements ExecutionEngine {
    @Override
    public ExecutionResult execute(OrderRequest request) {
        return ExecutionResult.success(
                UUID.randomUUID().toString(),
                request.getSymbol(),
                request.getSide(),
                request.getQuantity(),
                request.getPrice()
        );
    }
}
