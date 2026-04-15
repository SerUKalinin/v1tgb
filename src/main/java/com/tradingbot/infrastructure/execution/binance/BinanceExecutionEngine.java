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

/**
 * Исполнительный движок для отправки ордеров на Binance.
 * <p>
 * Активируется в профилях prod, testnet, live.
 */
import java.math.BigDecimal;

@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {

    private final BinanceClient binanceClient;

    /**
     * Выполняет торговый ордер на Binance.
     *
     * @param request запрос на исполнение ордера
     * @return результат исполнения
     */
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
                String exchangeOrderId = response.get("orderId").toString();
                // В реальном API Binance здесь также приходят сделки (fills), из которых можно вытащить externalTradeId и комиссии
                return ExecutionResult.success(
                        request.getOrderId(),
                        exchangeOrderId,
                        "trade-" + exchangeOrderId, // Mock trade ID
                        request.getSymbol(),
                        request.getSide(),
                        request.getAmount(),
                        request.getPrice(),
                        BigDecimal.ZERO,
                        "USDT",
                        request.getClientOrderId()
                );            }            return ExecutionResult.failure(request.getSymbol(), "Invalid response from Binance");
        } catch (Exception e) {
            log.error("[EXECUTION] Failed to execute order for {}", request.getSymbol(), e);
            return ExecutionResult.failure(request.getSymbol(), e.getMessage());
        }
    }
}