package com.tradingbot.infrastructure.binance;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Адаптер для работы с Binance API через ExecutionPort.
 * Изолирует специфику Binance от доменной логики.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceExecutionAdapter implements ExecutionPort {

    private final BinanceClient binanceClient;
    private BinanceExecutionAdapter self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy BinanceExecutionAdapter self) {
        this.self = self;
    }

    @Override
    public ExecutionResult placeOrder(ApprovedOrder order) {
        return self.doPlaceOrder(order);
    }

    @CircuitBreaker(name = "exchangeExecution", fallbackMethod = "fallbackPlaceOrder")
    @Retry(name = "exchangeExecution")
    public ExecutionResult doPlaceOrder(ApprovedOrder order) {
        log.info("[BINANCE-ADAPTER] Placing order: {} {} {}", order.getSymbol(), order.getSide(), order.getQuantity());
        
        Map<String, String> params = new HashMap<>();
        params.put("symbol", order.getSymbol());
        params.put("side", order.getSide().name());
        params.put("type", order.getType().name());
        params.put("quantity", normalizeQuantity(order.getQuantity()));
        params.put("newClientOrderId", sanitizeClientId(order.getClientOrderId()));
        
        if ("LIMIT".equals(order.getType().name())) {
            params.put("price", normalizePrice(order.getPrice()));
            params.put("timeInForce", "GTC");
        }
        try {
            Map response = binanceClient.post("/api/v3/order", params, Map.class, true);
            return mapToExecutionResult(order, response);
        } catch (Exception e) {
            log.error("[BINANCE-ADAPTER] Failed to place order {}: {}", order.getOrderId(), e.getMessage());
            ExecutionResult errorResult = mapErrorToResult(order.getOrderId(), e);
            if (errorResult.getStatus() == ExecutionResult.Status.REJECTED) {
                return errorResult;
            }
            throw e; 
        }
    }

    public ExecutionResult fallbackPlaceOrder(ApprovedOrder order, Throwable t) {
        log.error("[BINANCE-ADAPTER][FALLBACK] Circuit breaker open or retries exhausted for order {}: {}", 
                order.getOrderId(), t.getMessage());
        return mapErrorToResult(order.getOrderId(), (Exception) t);
    }
    @Override
    public ExecutionResult cancelOrder(String clientOrderId) {
        log.info("[BINANCE-ADAPTER] Cancelling order: {}", clientOrderId);
        // Реализация отмены будет добавлена при необходимости
        return ExecutionResult.failure(null, "Not implemented yet");
    }

    @Override
    public ExecutionResult getOrderStatus(String clientOrderId) {
        String sanitizedId = sanitizeClientId(clientOrderId);
        log.info("[BINANCE-ADAPTER] Querying order status: {} (sanitized: {})", clientOrderId, sanitizedId);
        Map<String, String> params = new HashMap<>();
        params.put("origClientOrderId", sanitizedId);
        // В Binance API символ обязателен для запроса ордера. 
        // В будущем стоит расширить интерфейс или хранить маппинг clientOrderId -> symbol
        params.put("symbol", "BTCUSDT"); 

        try {
            OrderStatusResponse response = binanceClient.get("/api/v3/order", params, OrderStatusResponse.class, true);
            return mapStatusResponse(response);
        } catch (Exception e) {
            log.error("[BINANCE-ADAPTER] Failed to get status for {}: {}", clientOrderId, e.getMessage());
            return ExecutionResult.timeout(null);
        }
    }

    @Override
    public Map<String, java.math.BigDecimal> getBalances() {
        try {
            Map accountInfo = binanceClient.getAccountInfo();
            java.util.List<Map<String, String>> balances = (java.util.List<Map<String, String>>) accountInfo.get("balances");
            
            return balances.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            b -> b.get("asset"),
                            b -> new java.math.BigDecimal(b.get("free"))
                    ));
        } catch (Exception e) {
            log.error("[BINANCE-ADAPTER] Failed to fetch balances: {}", e.getMessage());
            return Map.of();
        }
    }
    private String normalizeQuantity(java.math.BigDecimal quantity) {
        if (quantity == null) return "0";
        // Для BTCUSDT на Binance точность количества обычно 5 знаков (stepSize 0.00001)
        return quantity.setScale(5, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private String normalizePrice(java.math.BigDecimal price) {
        if (price == null) return "0";
        // Для пар к USDT точность цены обычно 2 знака (tickSize 0.01)
        return price.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * Нормализует clientOrderId под требования Binance:
     * regex: [a-zA-Z0-9-_]{1,36}
     */
    private String sanitizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId must not be null or empty for Binance execution");
        }
        
        // Оставляем только разрешенные символы: буквы, цифры, дефис, подчеркивание
        String sanitized = clientId.replaceAll("[^a-zA-Z0-9-_]", "");
        
        // Ограничиваем длину до 36 символов (Binance limit)
        if (sanitized.length() > 36) {
            sanitized = sanitized.substring(0, 36);
        }
        
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("clientOrderId contains no valid characters for Binance: " + clientId);
        }
        
        return sanitized;
    }
    private ExecutionResult mapToExecutionResult(ApprovedOrder order, Map response) {
        String status = (String) response.get("status");
        boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
        
        if (success) {
            return ExecutionResult.builder()
                    .orderId(order.getOrderId())
                    .exchangeOrderId(response.get("orderId").toString())
                    .executedQty(new java.math.BigDecimal((String) response.get("executedQty")))
                    .status(ExecutionResult.Status.SUCCESS)
                    .build();
        } else {
            return ExecutionResult.rejected(order.getOrderId(), status);
        }
    }

    private ExecutionResult mapErrorToResult(UUID orderId, Exception e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("400") || msg.contains("-1013") || msg.contains("-1111"))) {
            return ExecutionResult.rejected(orderId, msg);
        }
        if (msg != null && (msg.contains("Timeout") || msg.contains("504"))) {
            return ExecutionResult.timeout(orderId);
        }
        return ExecutionResult.failedIo(orderId, msg);
    }

    private ExecutionResult mapStatusResponse(OrderStatusResponse response) {
        boolean success = "FILLED".equals(response.getStatus());
        return ExecutionResult.builder()
                .exchangeOrderId(response.getExchangeOrderId())
                .executedQty(response.getExecutedQty())
                .status(success ? ExecutionResult.Status.SUCCESS : ExecutionResult.Status.REJECTED)
                .errorMessage(success ? null : response.getStatus())
                .build();
    }}
