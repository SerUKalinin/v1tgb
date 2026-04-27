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

            if (response != null && (response.containsKey("orderId") || response.containsKey("id"))) {
                String exchangeOrderId = response.getOrDefault("orderId", response.get("id")).toString();
                return ExecutionResult.success(
                        approvedOrder.getOrderId(),
                        exchangeOrderId,
                        "trade-" + exchangeOrderId,
                        approvedOrder.getSymbol(),
                        approvedOrder.getSide(),
                        approvedOrder.getQuantity(),
                        approvedOrder.getPrice(),
                        BigDecimal.ZERO,
                        "USDT",
                        approvedOrder.getClientOrderId()
                );
            }
            return ExecutionResult.failure(approvedOrder.getOrderId(), "Некорректный ответ от Binance");

        } catch (Exception e) {
            // 3. Обработка ошибок дублирования (если биржа вернула ошибку о существующем clientOrderId)
            if (e.getMessage() != null && e.getMessage().contains("Duplicate order sent")) {
                log.warn("[EXECUTION] Биржа сообщила о дубликате ордера {}. Синхронизируем состояние.", approvedOrder.getClientOrderId());
                // Здесь в реальной системе должен быть вызов GET /api/v3/order для получения статуса
                return ExecutionResult.failure(approvedOrder.getOrderId(), "Duplicate order on exchange: " + e.getMessage());
            }

            log.error("[EXECUTION] Ошибка при исполнении ордера {}: {}", approvedOrder.getSymbol(), e.getMessage());
            return ExecutionResult.failure(approvedOrder.getOrderId(), e.getMessage());
        }
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