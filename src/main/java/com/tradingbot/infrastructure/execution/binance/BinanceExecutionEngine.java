package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Исполнительный движок для отправки ордеров на Binance.
 * <p>
 * Активируется в профилях prod, testnet, live.
 */
@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {

    private final BinanceClient binanceClient;

    /**
     * Выполняет торговый ордер на Binance.
     * В Stage 3 принимает только ApprovedOrder.
     *
     * @param approvedOrder ордер, одобренный риск-менеджером
     * @return результат исполнения
     */
    @Override
    public ExecutionResult execute(ApprovedOrder approvedOrder) {
        log.info("[EXECUTION] Sending order to Binance: symbol={}, side={}, amount={}, clientOrderId={}",
                approvedOrder.getSymbol(), approvedOrder.getSide(), approvedOrder.getQuantity(), approvedOrder.getClientOrderId());

        try {
            Map<String, String> params = new HashMap<>();
            params.put("symbol", approvedOrder.getSymbol());
            params.put("side", approvedOrder.getSide().name());
            params.put("type", "MARKET");
            params.put("quantity", approvedOrder.getQuantity().toPlainString());
            params.put("newClientOrderId", approvedOrder.getClientOrderId());

            Map response = binanceClient.post("/api/v3/order", params, Map.class, true);
            log.info("[EXECUTION] Binance response: {}", response);

            if (response != null && (response.containsKey("orderId") || response.containsKey("id"))) {
                String exchangeOrderId = response.getOrDefault("orderId", response.get("id")).toString();
                return ExecutionResult.success(
                        approvedOrder.getOrderId(),
                        exchangeOrderId,
                        "trade-" + exchangeOrderId, // Mock trade ID
                        approvedOrder.getSymbol(),
                        approvedOrder.getSide(),
                        approvedOrder.getQuantity(),
                        approvedOrder.getPrice(),
                        BigDecimal.ZERO,
                        "USDT",
                        approvedOrder.getClientOrderId()
                );
            }
            return ExecutionResult.failure(approvedOrder.getOrderId(), "Invalid response from Binance");
        } catch (Exception e) {
            log.error("[EXECUTION] Failed to execute order for {}", approvedOrder.getSymbol(), e);
            return ExecutionResult.failure(approvedOrder.getOrderId(), e.getMessage());
        }    }
}