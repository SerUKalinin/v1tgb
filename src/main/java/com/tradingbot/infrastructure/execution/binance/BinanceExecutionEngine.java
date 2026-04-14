package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"testnet", "live"})
public class BinanceExecutionEngine implements ExecutionEngine {
    @Override
    public ExecutionResult execute(OrderRequest request) {
        // TODO: реальная интеграция с Binance REST + HMAC signature
        return ExecutionResult.failure(request.getSymbol(), "Binance integration not implemented yet");
    }
}