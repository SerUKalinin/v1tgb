package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.execution.ExecutionResult;
import com.tradingbot.domain.model.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"testnet", "live"})
public class BinanceExecutionEngine implements ExecutionEngine {
    @Override
    public ExecutionResult execute(Order order) {
        // TODO: реальная интеграция с Binance REST + HMAC signature
        throw new UnsupportedOperationException("Реализуй в Week 10");
    }
}