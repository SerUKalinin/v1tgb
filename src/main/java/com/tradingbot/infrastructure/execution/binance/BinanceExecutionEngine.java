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
 * Исполнительный движок ордеров для Binance.
 *
 * <p>Реализует {@link ExecutionEngine} и является адаптером между доменным
 * слоем исполнения и внешним {@link ExecutionPort} (Binance API).</p>
 *
 * <h2>Основные обязанности:</h2>
 * <ul>
 *     <li>Отправка ордеров на биржу через ExecutionPort</li>
 *     <li>Обеспечение идемпотентности на уровне clientOrderId</li>
 *     <li>Защита от повторной отправки уже обработанных ордеров</li>
 *     <li>Обработка состояния "UNKNOWN" через recovery-запрос</li>
 * </ul>
 *
 * <h2>Идемпотентность:</h2>
 * <p>
 * Проверяется наличие ордера в БД по clientOrderId.
 * Если ордер уже существует и находится в неготовом к исполнению статусе —
 * повторная отправка в ExecutionPort не выполняется.
 * </p>
 *
 * <h2>Recovery логика:</h2>
 * <p>
 * При статусе EXCHANGE_STATE_UNKNOWN выполняется повторная проверка
 * состояния ордера через getOrderStatus.
 * </p>
 */
@Slf4j
@Component
@Profile({"prod", "testnet", "live"})
@RequiredArgsConstructor
public class BinanceExecutionEngine implements ExecutionEngine {

    private final ExecutionPort executionPort;
    private final OrderRepository orderRepository;

    /**
     * Основной метод исполнения ордера.
     *
     * <p>Порядок выполнения:</p>
     * <ol>
     *     <li>Проверка идемпотентности по clientOrderId</li>
     *     <li>Отправка ордера через ExecutionPort</li>
     *     <li>Обработка неопределённого состояния биржи</li>
     * </ol>
     *
     * @param order доменный ордер
     * @return результат исполнения
     */
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

    /**
     * Проверка статуса ордера на бирже.
     *
     * @param clientOrderId идентификатор клиента ордера
     * @return актуальный статус исполнения
     */
    @Override
    public ExecutionResult verifyOrder(String clientOrderId) {
        return executionPort.getOrderStatus(clientOrderId);
    }

    /**
     * Внутренняя recovery-проверка состояния ордера.
     *
     * @param order доменный ордер
     * @return результат проверки статуса на бирже
     */
    private ExecutionResult verifyOrderInternal(Order order) {
        ExecutionResult recoveryResult = executionPort.getOrderStatus(order.getClientOrderId());
        log.info("[VERIFY][FLOW] orderId={} result={}", order.getClientOrderId(), recoveryResult.getStatus());
        return recoveryResult;
    }

    /**
     * Маппинг ранее сохранённого ордера в ExecutionResult.
     *
     * <p>Используется для идемпотентного возврата результата,
     * если ордер уже был обработан ранее.</p>
     *
     * @param entity сохранённая сущность ордера
     * @param order  доменный ордер
     * @return результат исполнения
     */
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