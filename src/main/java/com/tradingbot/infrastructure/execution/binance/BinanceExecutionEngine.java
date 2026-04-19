package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.persistence.entity.ExecutionIdempotencyEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@Primary
@Slf4j
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {
    private final BinanceClient binanceClient;
    private final ExecutionIdempotencyRepository idempotencyRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionResult execute(ApprovedOrder order) {
        String key = order.getClientOrderId();
        Instant now = Instant.now();

        // 1. Атомарная попытка вставки (Lock)
        int inserted = idempotencyRepository.tryInsertIdempotency(key, order.getOrderId(), now);

        if (inserted == 0) {
            // Запись уже существует, проверяем статус
            return idempotencyRepository.findById(key)
                    .map(record -> {
                        if ("SUCCESS".equals(record.getStatus())) {
                            log.info("[Idempotency] Order {} already executed. Returning cached result.", key);
                            return ExecutionResult.builder()
                                    .orderId(order.getOrderId())
                                    .clientOrderId(order.getClientOrderId())
                                    .exchangeOrderId(record.getExchangeOrderId())
                                    .symbol(order.getSymbol())
                                    .side(order.getSide())
                                    .executedQty(order.getQuantity())
                                    .success(true)
                                    .build();
                        } else if ("IN_PROGRESS".equals(record.getStatus())) {
                            log.warn("[Idempotency] Order {} is already in progress. Rejecting duplicate.", key);
                            throw new RuntimeException("Execution already in progress for order: " + key);
                        } else {
                            // Если FAILED, разрешаем повторную попытку, переводя в IN_PROGRESS
                            record.setStatus("IN_PROGRESS");
                            record.setUpdatedAt(Instant.now());
                            idempotencyRepository.save(record);
                            return null; // Продолжаем выполнение
                        }
                    })
                    .orElseGet(() -> {
                        // Редкий случай гонки при удалении, пробуем выполнить
                        return null;
                    });
        }

        try {
            // 2. Вызов биржи
            ExecutionResult result = binanceClient.placeOrder(order);

            // 3. Обновление результата
            ExecutionIdempotencyEntity finalRecord = idempotencyRepository.findById(key)
                    .orElseThrow(() -> new IllegalStateException("Idempotency record lost for " + key));

            if (result.isSuccess()) {
                finalRecord.setStatus("SUCCESS");
                finalRecord.setExchangeOrderId(result.getExchangeOrderId());
            } else {
                finalRecord.setStatus("FAILED");
                finalRecord.setErrorMessage(result.getErrorMessage());
            }
            idempotencyRepository.save(finalRecord);

            return result;

        } catch (Exception e) {
            log.error("[EXECUTION] Critical failure for order {}", key, e);
            markAsFailed(key, e.getMessage());
            return ExecutionResult.failure(order.getOrderId(), e.getMessage());
        }
    }

    private void markAsFailed(String key, String error) {
        idempotencyRepository.findById(key).ifPresent(record -> {
            record.setStatus("FAILED");
            record.setErrorMessage(error);
            idempotencyRepository.save(record);
        });
    }

    @Override
    public boolean cancelOrder(String exchangeOrderId, String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", exchangeOrderId);
            // Binance использует DELETE для отмены, убедитесь, что binanceClient это поддерживает
            binanceClient.post("/api/v3/order", params, Map.class, true);
            return true;
        } catch (Exception e) {
            log.error("[EXECUTION] Failed to cancel order {}", exchangeOrderId, e);
            return false;
        }
    }

    @Override
    public com.tradingbot.common.enums.OrderStatus getStatus(String exchangeOrderId, String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", exchangeOrderId);
            Map response = binanceClient.get("/api/v3/order", params, Map.class, true);
            String status = response.get("status").toString();
            return mapBinanceStatus(status);
        } catch (Exception e) {
            log.error("[EXECUTION] Failed to get status for order {}", exchangeOrderId, e);
            return com.tradingbot.common.enums.OrderStatus.ERROR;
        }
    }

    @Override
    public com.tradingbot.common.enums.OrderStatus getStatusByClientOrderId(String clientOrderId, String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("origClientOrderId", clientOrderId);
            Map response = binanceClient.get("/api/v3/order", params, Map.class, true);
            String status = response.get("status").toString();
            return mapBinanceStatus(status);
        } catch (Exception e) {
            log.error("[EXECUTION] Failed to get status by clientOrderId {}", clientOrderId, e);
            // Если ордер не найден, Binance вернет ошибку. В контексте сверки это может значить, что ордер не дошел.
            return com.tradingbot.common.enums.OrderStatus.REJECTED;
        }
    }

    private com.tradingbot.common.enums.OrderStatus mapBinanceStatus(String status) {
        return switch (status) {
            case "FILLED" -> com.tradingbot.common.enums.OrderStatus.FILLED;
            case "CANCELED", "REJECTED", "EXPIRED" -> com.tradingbot.common.enums.OrderStatus.REJECTED;
            case "NEW", "PARTIALLY_FILLED" -> com.tradingbot.common.enums.OrderStatus.EXECUTING;
            default -> com.tradingbot.common.enums.OrderStatus.EXECUTING;
        };
    }
}
