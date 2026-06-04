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
import com.tradingbot.tracing.*;
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

        ExecutionContext context = ExecutionContext.from(event);

        if (targetStatus == OrderStatus.FILLED) {
            order.fill(context, executedQty, executionPrice);
        } else if (targetStatus == OrderStatus.PARTIALLY_FILLED) {
            order.applyPartialFill(context, executedQty, executionPrice);
        } else if (targetStatus == OrderStatus.REJECTED) {
            order.markAsRejected(context, payload.getRejectionReason() != null ? payload.getRejectionReason() : "Unknown rejection");
        }        // 5. Обновление позиции через PositionService (только если есть реальное исполнение)
        if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
            ExecutionContext tradeEventContext = context.withNextStep(IdentityFactory.deriveEventId(context.attempt().executionId(), "trade-created"));

            TradeCreatedEvent tradeEvent = new TradeCreatedEvent(
                    tradeEventContext.identity(),
                    tradeEventContext.attempt(),
                    tradeEventContext.business(),
                    IdentityFactory.deriveEventId(tradeEventContext.attempt().executionId(), "trade-created"),
                    orderId,                    order.getSymbol(),
                    order.getStrategyId(),
                    executedQty,
                    executionPrice,
                    order.getSide(),
                    null, // stopLoss
                    null  // takeProfit
            );
            positionService.updatePosition(tradeEvent);
        }
    }
}
