package com.tradingbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Обработчик исполнения ордеров.
 * Слушает события из Outbox и инициирует выполнение через ExecutionEngine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionHandler implements OutboxConsumer {

    private final ExecutionEngine executionEngine;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    private static final String EVENT_TYPE = "ORDER_CREATED";

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equals(eventType);
    }

    @Override
    @Transactional
    public void consume(OutboxEventEntity event) throws Exception {
        log.info("[ИСПОЛНЕНИЕ] Получено событие {} для агрегата {}", event.getEventType(), event.getAggregateId());

        // 1. Десериализация данных ордера из payload
        OrderEntity order = objectMapper.readValue(event.getPayload(), OrderEntity.class);

        // 2. Проверка актуального статуса в БД (защита от повторного исполнения/идемпотентность)
        OrderEntity currentOrder = orderRepository.findById(order.getId())
                .orElseThrow(() -> new IllegalStateException("Ордер не найден в БД: " + order.getId()));

        if (!OrderStatus.PENDING_EXECUTION.name().equals(currentOrder.getStatus())) {
            log.warn("[ИСПОЛНЕНИЕ] Ордер {} уже находится в статусе {}. Пропуск.", 
                    currentOrder.getId(), currentOrder.getStatus());
            return;
        }

        try {
            // 3. Подготовка ApprovedOrder для ExecutionEngine
            ApprovedOrder approvedOrder = mapToApproved(currentOrder);

            log.info("[ИСПОЛНЕНИЕ] Отправка ордера {} (ClientOrderId: {}) в ExecutionEngine", 
                    approvedOrder.getOrderId(), approvedOrder.getClientOrderId());

            // 4. Вызов внешнего движка исполнения (Binance и т.д.)
            ExecutionResult result = executionEngine.execute(approvedOrder);

            // 5. Обработка результата через доменные методы
            if (result.isSuccess()) {
                currentOrder.markAsFilled(result.getExchangeOrderId());
                log.info("[ИСПОЛНЕНИЕ] Ордер {} успешно исполнен на бирже. ID: {}", 
                        currentOrder.getId(), result.getExchangeOrderId());
            } else {
                currentOrder.markAsRejected(result.getErrorMessage());
                log.error("[ИСПОЛНЕНИЕ] Ордер {} отклонен биржей: {}", 
                        currentOrder.getId(), result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("[ИСПОЛНЕНИЕ] Критическая ошибка при исполнении ордера {}: {}", 
                    currentOrder.getId(), e.getMessage());
            // Бросаем исключение дальше, чтобы OutboxProcessor мог применить retry-safe логику
            throw e;
        } finally {
            orderRepository.save(currentOrder);
        }
    }
    private ApprovedOrder mapToApproved(OrderEntity entity) {
        return ApprovedOrder.builder()
                .orderId(entity.getId())
                .clientOrderId(entity.getClientOrderId())
                .symbol(entity.getSymbol())
                .side(entity.getSide())
                .type(entity.getType())
                .quantity(entity.getQuantity())
                .price(entity.getPrice())
                .stopLoss(entity.getStopLoss())
                .takeProfit(entity.getTakeProfit())
                .strategyId(entity.getStrategyId())
                .approvedAt(entity.getCreatedAt())
                .build();
    }
}
