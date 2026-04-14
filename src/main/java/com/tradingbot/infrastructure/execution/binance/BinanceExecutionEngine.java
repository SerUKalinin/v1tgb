package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {
    
    private final BinanceClient binanceClient;
    @Override
    public ExecutionResult execute(OrderRequest request) {
        log.info("[EXECUTION] Sending order to Binance: symbol={}, side={}, amount={}, clientOrderId={}", 
                request.getSymbol(), request.getSide(), request.getAmount(), request.getClientOrderId());

        try {
            Map<String, String> params = new HashMap<>();
            params.put("symbol", request.getSymbol());
            params.put("side", request.getSide().name());
            params.put("type", "MARKET");
            params.put("quantity", request.getAmount().toPlainString());
            params.put("newClientOrderId", request.getClientOrderId());

            Map response = binanceClient.get("/api/v3/order", params, Map.class, true);

            if (response != null && response.containsKey("orderId")) {
                String orderId = response.get("orderId").toString();
                return ExecutionResult.success(orderId, request.getSymbol(), request.getSide(), request.getAmount(), request.getPrice());
            }            
            return ExecutionResult.failure(request.getSymbol(), "Invalid response from Binance");
        } catch (Exception e) {
            log.error("[EXECUTION] Failed to execute order for {}", request.getSymbol(), e);
            return ExecutionResult.failure(request.getSymbol(), e.getMessage());
        }
    }
}