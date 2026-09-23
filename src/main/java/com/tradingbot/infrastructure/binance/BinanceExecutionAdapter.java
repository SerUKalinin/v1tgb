package com.tradingbot.infrastructure.binance;

import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Адаптер интеграции с Binance API.
 *
 * <p>Инкапсулирует:
 * <ul>
 *     <li>размещение ордера</li>
 *     <li>получение статуса ордера</li>
 *     <li>отмену ордера</li>
 *     <li>получение балансов</li>
 *     <li>маппинг Binance ответа в ExecutionResult</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceExecutionAdapter
        implements ExecutionPort {

    private final BinanceClient binanceClient;

    private BinanceExecutionAdapter self;

    @Autowired
    public void setSelf(
            @Lazy BinanceExecutionAdapter self
    ) {
        this.self = self;
    }

    @Override
    public ExecutionResult placeOrder(Order order) {
        return self.doPlaceOrder(order);
    }

    @CircuitBreaker(
            name = "exchangeExecution",
            fallbackMethod = "fallbackPlaceOrder"
    )
    @Retry(name = "exchangeExecution")
    public ExecutionResult doPlaceOrder(
            Order order
    ) {

        log.info(
                "[BINANCE-ADAPTER] Placing order: {} {} {}",
                order.getSymbol(),
                order.getSide(),
                order.getQuantity()
        );

        Map<String, String> params =
                new HashMap<>();

        params.put(
                "symbol",
                order.getSymbol()
        );

        params.put(
                "side",
                order.getSide().name()
        );

        params.put(
                "type",
                order.getType().name()
        );

        params.put(
                "quantity",
                normalizeQuantity(
                        order.getQuantity()
                )
        );

        params.put(
                "newClientOrderId",
                sanitizeClientId(
                        order.getClientOrderId()
                )
        );

        if ("LIMIT".equals(order.getType().name())) {

            params.put(
                    "price",
                    normalizePrice(
                            order.getPrice()
                    )
            );

            params.put(
                    "timeInForce",
                    "GTC"
            );
        }

        try {

            Map response =
                    binanceClient.post(
                            "/api/v3/order",
                            params,
                            Map.class,
                            true
                    );

            return mapToExecutionResult(
                    order,
                    response
            );

        } catch (Exception e) {

            log.error(
                    "[BINANCE-ADAPTER] Failed to place order {}: {}",
                    order.getId(),
                    e.getMessage()
            );

            ExecutionResult errorResult =
                    BinanceStatusMapper.mapError(
                            e,
                            order.getId()
                    );

            if (errorResult.getStatus()
                    == ExecutionResult.Status.REJECTED) {

                return errorResult;
            }

            throw e;
        }
    }

    /**
     * Fallback при circuit breaker/retry failure.
     */
    public ExecutionResult fallbackPlaceOrder(
            Order order,
            Throwable t
    ) {

        log.error(
                "[BINANCE-ADAPTER][FALLBACK] "
                        + "Circuit breaker open or retries exhausted "
                        + "for order {}: {}",
                order.getId(),
                t.getMessage()
        );

        Exception exception =
                t instanceof Exception
                        ? (Exception) t
                        : new RuntimeException(t);

        return BinanceStatusMapper.mapError(
                exception,
                order.getId()
        );
    }

    /**
     * Отмена ордера.
     *
     * <p>Отдельный cancel flow пока остаётся
     * за пределами этого fix.</p>
     */
    @Override
    public ExecutionResult cancelOrder(
            String clientOrderId
    ) {

        log.info(
                "[BINANCE-ADAPTER] Cancelling order: {}",
                clientOrderId
        );

        return ExecutionResult.failure(
                null,
                "Not implemented yet"
        );
    }

    /**
     * Получает актуальное состояние Binance order.
     *
     * <p>Используется как authoritative exchange-status lookup
     * для recovery/reconciliation.</p>
     */
    @Override
    public ExecutionResult getOrderStatus(
            String symbol,
            String clientOrderId
    ) {

        String sanitizedClientOrderId =
                sanitizeClientId(
                        clientOrderId
                );

        String sanitizedSymbol =
                sanitizeSymbol(symbol);

        log.info(
                "[BINANCE-ADAPTER] Querying order status: "
                        + "symbol={}, clientOrderId={}, sanitizedClientOrderId={}",
                sanitizedSymbol,
                clientOrderId,
                sanitizedClientOrderId
        );

        Map<String, String> params =
                new HashMap<>();

        params.put(
                "symbol",
                sanitizedSymbol
        );

        params.put(
                "origClientOrderId",
                sanitizedClientOrderId
        );

        try {

            OrderStatusResponse response =
                    binanceClient.get(
                            "/api/v3/order",
                            params,
                            OrderStatusResponse.class,
                            true
                    );

            return mapStatusResponse(
                    response
            );

        } catch (Exception e) {

            log.error(
                    "[BINANCE-ADAPTER] Failed to get status "
                            + "for symbol={}, clientOrderId={}: {}",
                    sanitizedSymbol,
                    clientOrderId,
                    e.getMessage()
            );

            /*
             * Нельзя интерпретировать ошибку status lookup
             * как REJECTED/CANCELED.
             *
             * Биржевое состояние неизвестно.
             */
            return ExecutionResult.exchangeStateUnknown(
                    null
            );
        }
    }

    @Override
    public Map<String, BigDecimal> getBalances() {

        try {

            Map accountInfo =
                    binanceClient.getAccountInfo();

            java.util.List<Map<String, String>> balances =
                    (java.util.List<Map<String, String>>)
                            accountInfo.get("balances");

            return balances.stream()
                    .collect(
                            java.util.stream.Collectors.toMap(
                                    b -> b.get("asset"),
                                    b -> new BigDecimal(
                                            b.get("free")
                                    )
                            )
                    );

        } catch (Exception e) {

            log.error(
                    "[BINANCE-ADAPTER] Failed to fetch balances: {}",
                    e.getMessage()
            );

            return Map.of();
        }
    }

    /**
     * Маппинг ответа POST /api/v3/order.
     */
    private ExecutionResult mapToExecutionResult(
            Order order,
            Map response
    ) {

        String status =
                (String) response.get("status");

        String exchangeOrderId =
                response.get("orderId").toString();

        BigDecimal executedQty =
                new BigDecimal(
                        response
                                .get("executedQty")
                                .toString()
                );

        BigDecimal executedPrice =
                extractExecutedPrice(
                        response
                );

        ExecutionResult.Status mappedStatus =
                BinanceStatusMapper.mapBinanceStatus(
                        status
                );

        String exchangeTradeId =
                extractExchangeTradeId(
                        response
                );

        if (mappedStatus
                == ExecutionResult.Status.FILLED) {

            if (executedQty == null
                    || executedPrice == null) {

                log.error(
                        "[BINANCE-ADAPTER][INVARIANT] "
                                + "FILLED response has no fill data. "
                                + "orderId={}, exchangeOrderId={}",
                        order.getId(),
                        exchangeOrderId
                );

                return ExecutionResult.exchangeStateUnknown(
                        order.getId()
                );
            }

            return ExecutionResult.filled(
                    order.getId(),
                    exchangeOrderId,
                    exchangeTradeId,
                    order.getSymbol(),
                    order.getSide(),
                    executedQty,
                    executedPrice,
                    BigDecimal.ZERO,
                    "USDT",
                    order.getClientOrderId()
            );
        }

        if (mappedStatus
                == ExecutionResult.Status.PARTIALLY_FILLED) {

            if (executedQty == null
                    || executedQty.signum() <= 0
                    || executedPrice == null) {

                return ExecutionResult.exchangeStateUnknown(
                        order.getId()
                );
            }

            return ExecutionResult.partiallyFilled(
                    order.getId(),
                    exchangeOrderId,
                    exchangeTradeId,
                    order.getSymbol(),
                    order.getSide(),
                    executedQty,
                    executedPrice,
                    order.getClientOrderId()
            );
        }

        return ExecutionResult.of(
                order.getId(),
                exchangeOrderId,
                executedQty,
                executedPrice,
                mappedStatus,
                BinanceStatusMapper.isTerminalBinanceStatus(
                        status
                )
                        ? null
                        : status
        );
    }

    /**
     * Извлекает exchange trade id.
     */
    @SuppressWarnings("unchecked")
    private String extractExchangeTradeId(
            Map response
    ) {

        Object fillsObject =
                response.get("fills");

        if (!(fillsObject instanceof List<?> fills)) {
            return null;
        }

        for (Object fillObject : fills) {

            if (!(fillObject instanceof Map<?, ?> fill)) {
                continue;
            }

            Object tradeId =
                    fill.get("tradeId");

            if (tradeId != null
                    && !tradeId.toString().isBlank()) {

                return tradeId.toString();
            }
        }

        return null;
    }

    /**
     * Рассчитывает фактическую среднюю цену исполнения.
     *
     * <p>Приоритет:</p>
     *
     * <ol>
     *     <li>fills weighted average</li>
     *     <li>cummulativeQuoteQty / executedQty</li>
     *     <li>price</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private BigDecimal extractExecutedPrice(
            Map response
    ) {

        Object fillsObject =
                response.get("fills");

        if (fillsObject instanceof List<?> fills
                && !fills.isEmpty()) {

            BigDecimal totalQty =
                    BigDecimal.ZERO;

            BigDecimal totalQuoteQty =
                    BigDecimal.ZERO;

            for (Object fillObject : fills) {

                if (!(fillObject instanceof Map<?, ?> fill)) {
                    continue;
                }

                Object priceObject =
                        fill.get("price");

                Object qtyObject =
                        fill.get("qty");

                if (priceObject == null
                        || qtyObject == null) {
                    continue;
                }

                BigDecimal price =
                        new BigDecimal(
                                priceObject.toString()
                        );

                BigDecimal qty =
                        new BigDecimal(
                                qtyObject.toString()
                        );

                totalQty =
                        totalQty.add(qty);

                totalQuoteQty =
                        totalQuoteQty.add(
                                price.multiply(qty)
                        );
            }

            if (totalQty.signum() > 0) {

                return totalQuoteQty.divide(
                        totalQty,
                        8,
                        RoundingMode.HALF_UP
                );
            }
        }

        Object executedQtyObject =
                response.get("executedQty");

        Object cummulativeQuoteQtyObject =
                response.get("cummulativeQuoteQty");

        if (executedQtyObject != null
                && cummulativeQuoteQtyObject != null) {

            BigDecimal executedQty =
                    new BigDecimal(
                            executedQtyObject.toString()
                    );

            BigDecimal cummulativeQuoteQty =
                    new BigDecimal(
                            cummulativeQuoteQtyObject.toString()
                    );

            if (executedQty.signum() > 0) {

                return cummulativeQuoteQty.divide(
                        executedQty,
                        8,
                        RoundingMode.HALF_UP
                );
            }
        }

        Object priceObject =
                response.get("price");

        if (priceObject == null) {
            return null;
        }

        return new BigDecimal(
                priceObject.toString()
        );
    }

    /**
     * Маппинг ответа GET /api/v3/order.
     */
    private ExecutionResult mapStatusResponse(
            OrderStatusResponse response
    ) {

        if (response == null
                || response.getStatus() == null) {

            return ExecutionResult.exchangeStateUnknown(
                    null
            );
        }

        String rawStatus =
                response.getStatus();

        ExecutionResult.Status mappedStatus =
                BinanceStatusMapper.mapBinanceStatus(
                        rawStatus
                );

        BigDecimal executedQty =
                response.getExecutedQty();

        BigDecimal executedPrice =
                resolveExecutedPrice(
                        response
                );

        if ((mappedStatus
                == ExecutionResult.Status.FILLED
                || mappedStatus
                == ExecutionResult.Status.PARTIALLY_FILLED)
                && (executedQty == null
                || executedQty.signum() <= 0
                || executedPrice == null)) {

            log.error(
                    "[BINANCE-ADAPTER][INVARIANT] "
                            + "Exchange returned {} without valid fill data: "
                            + "exchangeOrderId={}, executedQty={}, executedPrice={}",
                    rawStatus,
                    response.getExchangeOrderId(),
                    executedQty,
                    executedPrice
            );

            return ExecutionResult.exchangeStateUnknown(
                    null
            );
        }

        return ExecutionResult.of(
                null,
                response.getExchangeOrderId(),
                executedQty,
                executedPrice,
                mappedStatus,
                BinanceStatusMapper.isTerminalBinanceStatus(
                        rawStatus
                )
                        ? null
                        : rawStatus
        );
    }

    /**
     * Рассчитывает среднюю цену исполнения
     * из статуса Binance.
     */
    private BigDecimal resolveExecutedPrice(
            OrderStatusResponse response
    ) {

        BigDecimal executedQty =
                response.getExecutedQty();

        BigDecimal cummulativeQuoteQty =
                response.getCummulativeQuoteQty();

        if (executedQty != null
                && cummulativeQuoteQty != null
                && executedQty.signum() > 0) {

            return cummulativeQuoteQty.divide(
                    executedQty,
                    8,
                    RoundingMode.HALF_UP
            );
        }

        return response.getPrice();
    }

    private String normalizeQuantity(
            BigDecimal quantity
    ) {

        if (quantity == null) {
            return "0";
        }

        return quantity
                .setScale(
                        5,
                        RoundingMode.DOWN
                )
                .stripTrailingZeros()
                .toPlainString();
    }

    private String normalizePrice(
            BigDecimal price
    ) {

        if (price == null) {
            return "0";
        }

        return price
                .setScale(
                        2,
                        RoundingMode.HALF_UP
                )
                .stripTrailingZeros()
                .toPlainString();
    }

    private String sanitizeClientId(
            String clientId
    ) {

        if (clientId == null
                || clientId.isBlank()) {

            throw new IllegalArgumentException(
                    "clientOrderId must not be null "
                            + "or empty for Binance execution"
            );
        }

        String sanitized =
                clientId.replaceAll(
                        "[^a-zA-Z0-9-_]",
                        ""
                );

        if (sanitized.length() > 36) {
            sanitized =
                    sanitized.substring(0, 36);
        }

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(
                    "clientOrderId contains no valid "
                            + "characters for Binance: "
                            + clientId
            );
        }

        return sanitized;
    }

    private String sanitizeSymbol(
            String symbol
    ) {

        if (symbol == null
                || symbol.isBlank()) {

            throw new IllegalArgumentException(
                    "symbol must not be null or empty"
            );
        }

        String sanitized =
                symbol.trim().toUpperCase();

        if (!sanitized.matches(
                "[A-Z0-9_]{1,20}"
        )) {

            throw new IllegalArgumentException(
                    "Invalid Binance symbol: "
                            + symbol
            );
        }

        return sanitized;
    }
}