package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Исполнительный движок, использующий ExecutionPort для взаимодействия с биржей.
 * Обеспечивает логику идемпотентности и восстановления на уровне приложения.
 */
@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {

    private final ExecutionPort executionPort;
    private final OrderRepository orderRepository;

    @Override
    public ExecutionResult execute(ApprovedOrder approvedOrder) {
        log.info("[EXECUTION] Попытка исполнения ордера через порт: symbol={}, side={}, amount={}, clientOrderId={}",
                approvedOrder.getSymbol(), approvedOrder.getSide(), approvedOrder.getQuantity(), approvedOrder.getClientOrderId());

        // 1. Проверка идемпотентности перед отправкой
        Optional<OrderEntity> existingOrder = orderRepository.findByClientOrderId(approvedOrder.getClientOrderId());
        if (existingOrder.isPresent()) {
            com.tradingbot.common.enums.OrderStatus status = existingOrder.get().getStatus();
            if (!com.tradingbot.domain.policy.OrderStateTransitionPolicy.isReadyForExecution(status)) {                log.warn("[EXECUTION] Ордер с clientOrderId {} уже обработан (статус: {}). Пропуск отправки.", 
                        approvedOrder.getClientOrderId(), existingOrder.get().getStatus());
                
                return mapToResult(existingOrder.get(), approvedOrder);
            }
        }
        // 2. Отправка через порт
        ExecutionResult result = executionPort.placeOrder(approvedOrder);

        // 3. Логика восстановления при таймаутах
        if (!result.isSuccess() && "TIMEOUT".equals(result.getErrorMessage())) {
            log.warn("[VERIFY][FLOW] Timeout detected for orderId={}, starting recovery", approvedOrder.getClientOrderId());
            return verifyOrderInternal(approvedOrder);
        }

        return result;
    }

    @Override
    public ExecutionResult verifyOrder(String clientOrderId) {
        return executionPort.getOrderStatus(clientOrderId);
    }
    private ExecutionResult verifyOrderInternal(ApprovedOrder approvedOrder) {
        ExecutionResult recoveryResult = executionPort.getOrderStatus(approvedOrder.getClientOrderId());
        
        if (recoveryResult.isSuccess()) {
            log.info("[VERIFY][FLOW] orderId={} result=RECOVERED_SUCCESS", approvedOrder.getClientOrderId());
            return ExecutionResult.success(
                    approvedOrder.getOrderId(),
                    recoveryResult.getExchangeOrderId(),
                    "recovered-" + recoveryResult.getExchangeOrderId(),
                    approvedOrder.getSymbol(),
                    approvedOrder.getSide(),
                    recoveryResult.getExecutedQty(),
                    approvedOrder.getPrice(),
                    BigDecimal.ZERO,
                    "USDT",
                    approvedOrder.getClientOrderId()
            );
        }
        
        return recoveryResult;
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