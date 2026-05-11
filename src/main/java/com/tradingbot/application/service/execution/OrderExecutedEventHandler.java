package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderPort;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Обработчик события ORDER_EXECUTED.
 * Отвечает за финальную обработку исполненного ордера и обновление позиций.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutedEventHandler implements OutboxConsumer {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final OrderPort orderPort;
    private final PositionService positionService;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_EXECUTED".equals(eventType);
    }

    @Override
    @Transactional
    public void consume(OutboxEventEntity event) throws Exception {
        // 1. Проверка идемпотентности
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[ORDER-EXECUTED-HANDLER] Event {} already processed, skipping", event.getId());
            return;
        }

        // 2. Десериализация данных в строгое DTO
        OrderExecutedEvent payload = objectMapper.readValue(event.getPayload(), OrderExecutedEvent.class);
        UUID orderId = payload.getOrderId();

        log.info("[ORDER-EXECUTED-HANDLER] Processing execution for order {}. Status: {}, Qty: {}, Price: {}", 
                orderId, payload.getStatus(), payload.getQuantity(), payload.getPrice());

        // 3. Поиск ордера через порт (агрегат)
        Order order = orderPort.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        // 4. Применение результата исполнения
        BigDecimal executedQty = payload.getQuantity();
        BigDecimal executionPrice = payload.getPrice();
        OrderStatus targetStatus = payload.getStatus();

        if (targetStatus == OrderStatus.FILLED) {
            order.fill(null, executedQty, executionPrice);
        } else if (targetStatus == OrderStatus.PARTIALLY_FILLED) {
            order.applyPartialFill(executedQty, executionPrice);
        }

        // 5. Обновление позиции через PositionService (только если есть реальное исполнение)
        if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
            ExecutionContext context = ExecutionContext.restore(
                    event.getAggregateId(),
                    event.getCorrelationId(),
                    event.getSignalId(),
                    event.getOrderId(),
                    event.getExecutionId(),
                    event.getEventId()
            );            
            TradeCreatedEvent tradeEvent = new TradeCreatedEvent(                    context,
                    UUID.randomUUID(),
                    orderId,
                    order.getSymbol(),
                    order.getStrategyId(),
                    executedQty,
                    executionPrice,
                    order.getSide(),
                    null, // stopLoss
                    null  // takeProfit
            );
            positionService.updatePosition(tradeEvent);
        }
        // 6. Сохранение ордера
        orderPort.save(order);
        
        // 7. Пометка события как обработанного
        idempotencyService.markAsProcessed(event.getId(), "OrderExecutedEventHandler");
    }
}
