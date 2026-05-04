package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderEventPayload;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderPort;import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
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

    private final StateTransitionExecutor transitionExecutor;

    @Override
    @Transactional
    public void consume(OutboxEventEntity event) throws Exception {
        // 1. Проверка идемпотентности
        if (idempotencyService.isAlreadyProcessed(event.getId())) {
            log.info("[ORDER-EXECUTED-HANDLER] Event {} already processed, skipping", event.getId());
            return;
        }

        // 2. Десериализация данных
        OrderEventPayload payload = objectMapper.readValue(event.getPayload(), OrderEventPayload.class);
        UUID orderId = payload.getOrderId();

        // 3. Поиск ордера через репозиторий для получения managed entity
        OrderEntity entity = ((com.tradingbot.infrastructure.persistence.adapter.JpaOrderAdapter)orderPort).getEntityById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order entity not found: " + orderId));
        Order order = orderPort.findById(orderId).get();

        log.info("[ORDER-EXECUTED-HANDLER] Processing execution for order {}. Status: {}, Qty: {}, Price: {}", 
                orderId, payload.getStatus(), payload.getQuantity(), payload.getPrice());

        // 4. Применение результата исполнения через StateTransitionExecutor
        BigDecimal executedQty = payload.getQuantity();
        BigDecimal executionPrice = payload.getPrice();
        OrderStatus targetStatus = OrderStatus.valueOf(payload.getStatus());

        transitionExecutor.execute(order, entity, targetStatus, () -> {
            if (targetStatus == OrderStatus.FILLED) {
                order.fill(null, executedQty, executionPrice);
            } else if (targetStatus == OrderStatus.PARTIALLY_FILLED) {
                order.markAsPartiallyFilled(executedQty, executionPrice);
            }
        });

        // 5. Обновление позиции через PositionService (только если есть реальное исполнение)
        if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
            TradeCreatedEvent tradeEvent = new TradeCreatedEvent(
                    event.getId(),
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
        } else {
            log.debug("[FINALITY] Skip trade creation, no execution: orderId={}, status={}", orderId, targetStatus);
        }

        // 6. Сохранение ордера
        orderPort.save(order);
        
        // 7. Пометка события как обработанного
        idempotencyService.markAsProcessed(event.getId(), "OrderExecutedEventHandler");
    }}
