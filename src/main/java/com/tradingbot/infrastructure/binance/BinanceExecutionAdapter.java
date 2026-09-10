package com.tradingbot.infrastructure.binance;

import com.tradingbot.domain.model.Order;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Адаптер интеграции с Binance API.
 *
 * <p>Реализует {@link ExecutionPort} и инкапсулирует:
 * <ul>
 *   <li>HTTP-вызовы Binance API</li>
 *   <li>нормализацию параметров ордера</li>
 *   <li>маппинг статусов Binance → доменная модель</li>
 *   <li>обработку ошибок и fallback-логику</li>
 * </ul>
 *
 * <p>Является антикоррупционным слоем между доменом и внешней биржей.</p>
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
    public ExecutionResult placeOrder(Order order) {
        return self.doPlaceOrder(order);
    }

    @CircuitBreaker(name = "exchangeExecution", fallbackMethod = "fallbackPlaceOrder")
    @Retry(name = "exchangeExecution")
    public ExecutionResult doPlaceOrder(Order order) {
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
            log.error("[BINANCE-ADAPTER] Failed to place order {}: {}", order.getId(), e.getMessage());
            ExecutionResult errorResult = BinanceStatusMapper.mapError(e, order.getId());
            if (errorResult.getStatus() == ExecutionResult.Status.REJECTED) {
                return errorResult;
            }
            throw e;
        }
    }

    /**
     * Fallback при срабатывании circuit breaker или исчерпании retry.
     */
    public ExecutionResult fallbackPlaceOrder(Order order, Throwable t) {
        log.error("[BINANCE-ADAPTER][FALLBACK] Circuit breaker open or retries exhausted for order {}: {}",
                order.getId(), t.getMessage());
        return BinanceStatusMapper.mapError((Exception) t, order.getId());
    }

    @Override
    public ExecutionResult cancelOrder(String clientOrderId) {
        log.info("[BINANCE-ADAPTER] Cancelling order: {}", clientOrderId);
        return ExecutionResult.failure(null, "Not implemented yet");
    }

    @Override
    public ExecutionResult getOrderStatus(String clientOrderId) {
        String sanitizedId = sanitizeClientId(clientOrderId);
        log.info("[BINANCE-ADAPTER] Querying order status: {} (sanitized: {})", clientOrderId, sanitizedId);

        Map<String, String> params = new HashMap<>();
        params.put("origClientOrderId", sanitizedId);
        params.put("symbol", "BTCUSDT");

        try {
            OrderStatusResponse response =
                    binanceClient.get("/api/v3/order", params, OrderStatusResponse.class, true);

            return mapStatusResponse(response);
        } catch (Exception e) {
            log.error("[BINANCE-ADAPTER] Failed to get status for {}: {}", clientOrderId, e.getMessage());
            return ExecutionResult.exchangeStateUnknown(null);
        }
    }

    @Override
    public Map<String, BigDecimal> getBalances() {
        try {
            Map accountInfo = binanceClient.getAccountInfo();

            java.util.List<Map<String, String>> balances =
                    (java.util.List<Map<String, String>>) accountInfo.get("balances");

            return balances.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            b -> b.get("asset"),
                            b -> new BigDecimal(b.get("free"))
                    ));
        } catch (Exception e) {
            log.error("[BINANCE-ADAPTER] Failed to fetch balances: {}", e.getMessage());
            return Map.of();
        }
    }

    private String normalizeQuantity(BigDecimal quantity) {
        if (quantity == null) return "0";
        return quantity.setScale(5, java.math.RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String normalizePrice(BigDecimal price) {
        if (price == null) return "0";
        return price.setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String sanitizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId must not be null or empty for Binance execution");
        }

        String sanitized = clientId.replaceAll("[^a-zA-Z0-9-_]", "");

        if (sanitized.length() > 36) {
            sanitized = sanitized.substring(0, 36);
        }

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("clientOrderId contains no valid characters for Binance: " + clientId);
        }

        return sanitized;
    }

    private ExecutionResult mapToExecutionResult(Order order, Map response) {
        String status = (String) response.get("status");
        String exchangeOrderId = response.get("orderId").toString();
        BigDecimal executedQty = new BigDecimal((String) response.get("executedQty"));
        BigDecimal executedPrice = new BigDecimal((String) response.get("price"));
        ExecutionResult.Status mappedStatus = BinanceStatusMapper.mapBinanceStatus(status);

        return ExecutionResult.of(
                order.getId(),
                exchangeOrderId,
                executedQty,
                executedPrice,
                mappedStatus,
                BinanceStatusMapper.isTerminalBinanceStatus(status) ? null : status
        );
    }

    private ExecutionResult mapStatusResponse(OrderStatusResponse response) {
        String status = response.getStatus();

        return ExecutionResult.of(
                null,
                response.getExchangeOrderId(),
                response.getExecutedQty(),
                response.getPrice(),
                BinanceStatusMapper.mapBinanceStatus(status),
                BinanceStatusMapper.isTerminalBinanceStatus(status) ? null : status
        );
    }
}