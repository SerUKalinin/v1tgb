package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.Order;
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
    public ExecutionResult execute(Order order) {
        log.info("[EXECUTION] Попытка исполнения ордера через порт: symbol={}, side={}, amount={}, clientOrderId={}",
                order.getSymbol(), order.getSide(), order.getQuantity(), order.getClientOrderId());

        Optional<OrderEntity> existingOrder = orderRepository.findByClientOrderId(order.getClientOrderId());
        if (existingOrder.isPresent()) {
            com.tradingbot.common.enums.OrderStatus status = existingOrder.get().getStatus();
            if (!com.tradingbot.domain.policy.OrderStateTransitionPolicy.isReadyForExecution(status)) {
                log.warn("[EXECUTION] Ордер с clientOrderId {} уже обработан (статус: {}). Пропуск отправки.",
                        order.getClientOrderId(), existingOrder.get().getStatus());

                return mapToResult(existingOrder.get(), order);
            }
        }

        ExecutionResult result = executionPort.placeOrder(order);

        if (!result.isFilled() && result.getStatus() == ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN) {
            log.warn("[VERIFY][FLOW] Exchange state unknown for orderId={}, starting recovery", order.getClientOrderId());
            return verifyOrderInternal(order);
        }

        return result;
    }

    @Override
    public ExecutionResult verifyOrder(String clientOrderId) {
        return executionPort.getOrderStatus(clientOrderId);
    }

    private ExecutionResult verifyOrderInternal(Order order) {
        ExecutionResult recoveryResult = executionPort.getOrderStatus(order.getClientOrderId());
        log.info("[VERIFY][FLOW] orderId={} result={}", order.getClientOrderId(), recoveryResult.getStatus());
        return recoveryResult;
    }

    private ExecutionResult mapToResult(OrderEntity entity, Order order) {
        return ExecutionResult.filled(
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
