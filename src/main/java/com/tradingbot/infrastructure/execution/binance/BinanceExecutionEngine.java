package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Исполнительный движок для отправки ордеров на Binance с поддержкой идемпотентности.
 */
@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {

    private final BinanceClient binanceClient;
    private final OrderRepository orderRepository;

    @Override
    public ExecutionResult execute(ApprovedOrder approvedOrder) {
        log.info("[EXECUTION] Попытка исполнения ордера: symbol={}, side={}, amount={}, clientOrderId={}",
                approvedOrder.getSymbol(), approvedOrder.getSide(), approvedOrder.getQuantity(), approvedOrder.getClientOrderId());

        // 1. Проверка идемпотентности перед отправкой
        Optional<OrderEntity> existingOrder = orderRepository.findByClientOrderId(approvedOrder.getClientOrderId());
        if (existingOrder.isPresent() && !"PENDING_EXECUTION".equals(existingOrder.get().getStatus())) {
            log.warn("[EXECUTION] Ордер с clientOrderId {} уже обработан (статус: {}). Пропуск отправки.", 
                    approvedOrder.getClientOrderId(), existingOrder.get().getStatus());
            
            return mapToResult(existingOrder.get(), approvedOrder);
        }

        try {
            Map<String, String> params = new HashMap<>();
            params.put("symbol", approvedOrder.getSymbol());
            params.put("side", approvedOrder.getSide().name());
            params.put("type", "MARKET");
            params.put("quantity", approvedOrder.getQuantity().toPlainString());
            params.put("newClientOrderId", approvedOrder.getClientOrderId());

            // 2. Отправка на биржу
            Map response = binanceClient.post("/api/v3/order", params, Map.class, true);
            log.info("[EXECUTION] Ответ Binance: {}", response);

            return parseResponse(response, approvedOrder);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            boolean isTimeout = e instanceof java.util.concurrent.TimeoutException || errorMsg.contains("timeout");
            boolean isDuplicate = errorMsg.contains("-2010") || errorMsg.contains("Duplicate");

            if (isTimeout || isDuplicate) {
                log.warn("[VERIFY][FLOW] type={} orderId={} result=STARTING_RECOVERY", 
                        isTimeout ? "TIMEOUT" : "DUPLICATE", approvedOrder.getClientOrderId());
                
                OrderStatusResponse verify = verifyOrder(approvedOrder.getClientOrderId());
                
                if ("FILLED".equals(verify.getStatus()) || "PARTIALLY_FILLED".equals(verify.getStatus())) {
                    log.info("[VERIFY][FLOW] type={} orderId={} result=RECOVERED_SUCCESS", 
                            isTimeout ? "TIMEOUT" : "DUPLICATE", approvedOrder.getClientOrderId());
                    return ExecutionResult.success(
                            approvedOrder.getOrderId(),
                            verify.getExchangeOrderId(),
                            "trade-" + verify.getExchangeOrderId(),
                            approvedOrder.getSymbol(),
                            approvedOrder.getSide(),
                            verify.getExecutedQty(),
                            approvedOrder.getPrice(),
                            BigDecimal.ZERO,
                            "USDT",
                            approvedOrder.getClientOrderId()
                    );
                }
                
                if (OrderStatusResponse.ORDER_NOT_FOUND.equals(verify.getStatus())) {
                    log.error("[VERIFY][FLOW] type={} orderId={} result=ORDER_NOT_FOUND", 
                            isTimeout ? "TIMEOUT" : "DUPLICATE", approvedOrder.getClientOrderId());
                    return ExecutionResult.failure(approvedOrder.getOrderId(), "Order not found after " + (isTimeout ? "timeout" : "duplicate"));
                }
            }

            log.error("[EXECUTION] Ошибка при исполнении ордера {}: {}", approvedOrder.getSymbol(), errorMsg);
            return ExecutionResult.failure(approvedOrder.getOrderId(), errorMsg);
        }
    }
    /**
     * Проверяет статус ордера на Binance по clientOrderId.
     * Чистый read-only метод.
     */
    public OrderStatusResponse verifyOrder(String clientOrderId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("origClientOrderId", clientOrderId);

            Map response = binanceClient.get("/api/v3/order", params, Map.class, true);
            
            if (response == null) {
                return OrderStatusResponse.builder().status(OrderStatusResponse.UNKNOWN).build();
            }

            String status = response.get("status").toString();
            BigDecimal executedQty = new BigDecimal(response.get("executedQty").toString());
            String exchangeOrderId = response.get("orderId").toString();

            log.info("[VERIFY][BINANCE] orderId={} status={}", clientOrderId, status);

            return OrderStatusResponse.builder()
                    .status(status)
                    .executedQty(executedQty)
                    .exchangeOrderId(exchangeOrderId)
                    .clientOrderId(clientOrderId)
                    .build();

        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().contains("404") || e.getMessage().contains("Order does not exist"))) {
                log.warn("[VERIFY][BINANCE] Order {} not found on exchange", clientOrderId);
                return OrderStatusResponse.builder().status(OrderStatusResponse.ORDER_NOT_FOUND).build();
            }
            if (e instanceof java.util.concurrent.TimeoutException || (e.getMessage() != null && e.getMessage().contains("timeout"))) {
                log.error("[VERIFY][BINANCE] Timeout verifying order {}", clientOrderId);
                return OrderStatusResponse.builder().status(OrderStatusResponse.UNKNOWN).build();
            }
            log.error("[VERIFY][BINANCE] Error verifying order {}: {}", clientOrderId, e.getMessage());
            return OrderStatusResponse.builder().status(OrderStatusResponse.UNKNOWN).build();
        }
    }

    private ExecutionResult parseResponse(Map response, ApprovedOrder approvedOrder) {
        if (response != null && (response.containsKey("orderId") || response.containsKey("id"))) {
            String exchangeOrderId = response.getOrDefault("orderId", response.get("id")).toString();
            BigDecimal executedQty = response.containsKey("executedQty") 
                    ? new BigDecimal(response.get("executedQty").toString())
                    : approvedOrder.getQuantity();

            return ExecutionResult.success(
                    approvedOrder.getOrderId(),
                    exchangeOrderId,
                    "trade-" + exchangeOrderId,
                    approvedOrder.getSymbol(),
                    approvedOrder.getSide(),
                    executedQty,
                    approvedOrder.getPrice(),
                    BigDecimal.ZERO,
                    "USDT",
                    approvedOrder.getClientOrderId()
            );
        }
        return ExecutionResult.failure(approvedOrder.getOrderId(), "Некорректный ответ от Binance");
    }

    private ExecutionResult mapToResult(OrderEntity entity, ApprovedOrder approved) {
        return ExecutionResult.success(
                entity.getId(),
                entity.getExchangeOrderId(),
                "existing-trade",
                entity.getSymbol(),
                entity.getSide(),
                entity.getQuantity(),
                entity.getPrice(),
                BigDecimal.ZERO,
                "USDT",
                entity.getClientOrderId()
        );
    }
}