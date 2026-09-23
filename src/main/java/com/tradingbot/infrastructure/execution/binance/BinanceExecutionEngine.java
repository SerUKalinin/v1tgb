package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
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
 * Исполнительный движок Binance.
 *
 * <p>Отвечает за execution orchestration на уровне infrastructure.</p>
 *
 * <p>Recovery не изменяет состояние Order напрямую.
 * Он только получает authoritative exchange state,
 * который затем обрабатывается application execution/reconciliation flow.</p>
 */
@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine
        implements ExecutionEngine {

    private final ExecutionPort executionPort;
    private final OrderRepository orderRepository;

    @Override
    public ExecutionResult execute(
            Order order
    ) {

        log.info(
                "[EXECUTION] Попытка исполнения ордера через порт: "
                        + "symbol={}, side={}, amount={}, clientOrderId={}",
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getClientOrderId()
        );

        Optional<OrderEntity> existingOrder =
                orderRepository.findByClientOrderId(
                        order.getClientOrderId()
                );

        if (existingOrder.isPresent()) {

            com.tradingbot.common.enums.OrderStatus status =
                    existingOrder.get().getStatus();

            if (!com.tradingbot.domain.policy
                    .OrderStateTransitionPolicy
                    .isReadyForExecution(status)) {

                log.warn(
                        "[EXECUTION] Ордер с clientOrderId {} "
                                + "уже обработан (статус: {}). "
                                + "Пропуск отправки.",
                        order.getClientOrderId(),
                        status
                );

                return mapToResult(
                        existingOrder.get(),
                        order
                );
            }
        }

        ExecutionResult result =
                executionPort.placeOrder(order);

        if (!result.isFilled()
                && result.getStatus()
                == ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN) {

            log.warn(
                    "[VERIFY][FLOW] Exchange state unknown "
                            + "for orderId={}, starting recovery",
                    order.getClientOrderId()
            );

            return verifyOrderInternal(
                    order
            );
        }

        return result;
    }

    /**
     * Проверяет состояние ордера по clientOrderId.
     *
     * <p>Symbol извлекается из DB, поскольку Binance
     * требует symbol для GET /api/v3/order.</p>
     */
    @Override
    public ExecutionResult verifyOrder(
            String clientOrderId
    ) {

        Optional<OrderEntity> existingOrder =
                orderRepository.findByClientOrderId(
                        clientOrderId
                );

        if (existingOrder.isEmpty()) {

            log.warn(
                    "[VERIFY] Order not found in DB: clientOrderId={}",
                    clientOrderId
            );

            return ExecutionResult.exchangeStateUnknown(
                    null
            );
        }

        return executionPort.getOrderStatus(
                existingOrder.get().getSymbol(),
                clientOrderId
        );
    }

    /**
     * Recovery-проверка состояния конкретного ордера.
     */
    private ExecutionResult verifyOrderInternal(
            Order order
    ) {

        ExecutionResult recoveryResult =
                executionPort.getOrderStatus(
                        order.getSymbol(),
                        order.getClientOrderId()
                );

        log.info(
                "[VERIFY][FLOW] orderId={} result={}",
                order.getClientOrderId(),
                recoveryResult.getStatus()
        );

        /*
         * ВАЖНО:
         * не оборачиваем recoveryResult в новый FILLED result.
         *
         * BinanceExecutionAdapter уже вернул authoritative
         * status + executedQty + executedPrice.
         */
        return recoveryResult;
    }

    /**
     * Маппинг ранее сохранённого ордера
     * в ExecutionResult.
     */
    private ExecutionResult mapToResult(
            OrderEntity entity,
            Order order
    ) {

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