package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Обработчик outbox-события ORDER_EXECUTED.
 *
 * <p>ORDER_EXECUTED является downstream-событием после завершения
 * execution owner'ом всех изменений агрегата Order.</p>
 *
 * <p>Handler не изменяет Order повторно и не обновляет PositionService
 * напрямую. Для фактически исполненной сделки делегирует создание Trade
 * в TradeService, который публикует TRADE_CREATED.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutedEventHandler implements OutboxConsumer {

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TradeService tradeService;

    @Override
    public boolean supports(String eventType) {
        return "ORDER_EXECUTED".equals(eventType);
    }

    /**
     * Обрабатывает ORDER_EXECUTED.
     *
     * <p>Порядок:
     * <ul>
     *     <li>десериализация payload;</li>
     *     <li>проверка идемпотентности по OutboxEventEntity.eventId;</li>
     *     <li>для FILLED/PARTIALLY_FILLED создание Trade через TradeService;</li>
     *     <li>фиксация идемпотентности.</li>
     * </ul>
     *
     * <p>Агрегат Order здесь не изменяется:
     * его terminal state уже установлен execution owner'ом
     * до публикации ORDER_EXECUTED.</p>
     */
    @Override
    @Transactional
    public void consume(OutboxEventEntity event) throws Exception {

        UUID eventId = event.getEventId();

        if (eventId == null) {
            throw new IllegalStateException(
                    "ORDER_EXECUTED outbox event has no eventId"
            );
        }

        OrderExecutedEvent payload =
                objectMapper.readValue(
                        event.getPayload(),
                        OrderExecutedEvent.class
                );

        UUID orderId = payload.getOrderId();
        UUID executionId = payload.getAttempt().executionId();

        if (idempotencyService.isAlreadyProcessed(eventId)) {
            log.info(
                    "[ORDER-EXECUTED-HANDLER] Outbox event {} " +
                            "for executionId {} and order {} already processed, skipping",
                    eventId,
                    executionId,
                    orderId
            );
            return;
        }

        log.info(
                "[ORDER-EXECUTED-HANDLER] Processing execution for order {}. " +
                        "Status: {}, Qty: {}, Price: {}, exchangeTradeId: {}",
                orderId,
                payload.getStatus(),
                payload.getQuantity(),
                payload.getPrice(),
                payload.getExchangeTradeId()
        );

        if (isTradeCreationRequired(payload)) {
            ExecutionContext context = ExecutionContext.of(
                    payload.getIdentity(),
                    payload.getAttempt(),
                    payload.getBusiness()
            );

            tradeService.onOrderExecuted(payload, context);
        }

        idempotencyService.markAsProcessed(
                eventId,
                "OrderExecutedEventHandler"
        );
    }

    private boolean isTradeCreationRequired(OrderExecutedEvent payload) {
        if (payload.getStatus() != OrderStatus.FILLED
                && payload.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            return false;
        }

        BigDecimal quantity = payload.getQuantity();
        BigDecimal price = payload.getPrice();

        return quantity != null
                && quantity.compareTo(BigDecimal.ZERO) > 0
                && price != null
                && payload.getExchangeTradeId() != null
                && !payload.getExchangeTradeId().isBlank();
    }
}