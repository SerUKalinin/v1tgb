package com.tradingbot.application.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Обработчик downstream-события ORDER_EXECUTED.
 *
 * Поддерживает:
 *
 * ORDER_EXECUTED
 *
 * и checkpoint-specific:
 *
 * ORDER_EXECUTED:<checkpoint-id>
 *
 * Order здесь повторно не изменяется.
 *
 * Execution owner уже завершил lifecycle Order
 * до публикации ORDER_EXECUTED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutedEventHandler implements OutboxConsumer {

    private static final String BASE_EVENT_TYPE =
            "ORDER_EXECUTED";

    private static final String CONSUMER_NAME =
            "OrderExecutedEventHandler";

    private final IdempotencyService idempotencyService;

    private final ObjectMapper objectMapper;

    private final TradeService tradeService;

    @Override
    public boolean supports(
            String eventType
    ) {

        /*
         * F4.3:
         *
         * обычный:
         * ORDER_EXECUTED
         *
         * cumulative checkpoint:
         * ORDER_EXECUTED:<checkpoint-id>
         */
        return BASE_EVENT_TYPE.equals(eventType)
                || (
                eventType != null
                        && eventType.startsWith(
                        BASE_EVENT_TYPE + ":"
                )
        );
    }

    /**
     * Обрабатывает ORDER_EXECUTED
     * и checkpoint-specific ORDER_EXECUTED:<checkpoint>.
     */
    @Override
    @Transactional
    public void consume(
            OutboxEvent event
    ) throws Exception {

        if (event == null) {

            throw new IllegalArgumentException(
                    "OutboxEvent cannot be null"
            );
        }

        UUID eventId =
                event.eventId();

        if (eventId == null) {

            throw new IllegalStateException(
                    "ORDER_EXECUTED outbox event has no eventId"
            );
        }

        String eventType =
                event.eventType();

        if (!supports(eventType)) {

            throw new IllegalArgumentException(
                    "Unsupported event type for " +
                            CONSUMER_NAME +
                            ": " +
                            eventType
            );
        }

        OrderExecutedEvent payload =
                objectMapper.readValue(
                        event.payload(),
                        OrderExecutedEvent.class
                );

        UUID orderId =
                payload.getOrderId();

        UUID executionId =
                payload
                        .getAttempt()
                        .executionId();

        if (idempotencyService.isAlreadyProcessed(
                eventId
        )) {

            log.info(
                    "[ORDER-EXECUTED-HANDLER] Event {} already processed. " +
                            "eventType={}, orderId={}, executionId={}. Skipping.",
                    eventId,
                    eventType,
                    orderId,
                    executionId
            );

            return;
        }

        log.info(
                "[ORDER-EXECUTED-HANDLER] Processing event. " +
                        "eventId={}, eventType={}, orderId={}, " +
                        "executionId={}, status={}, qty={}, price={}, " +
                        "exchangeTradeId={}",
                eventId,
                eventType,
                orderId,
                executionId,
                payload.getStatus(),
                payload.getQuantity(),
                payload.getPrice(),
                payload.getExchangeTradeId()
        );

        if (isTradeCreationRequired(
                payload
        )) {

            ExecutionContext context =
                    ExecutionContext.of(
                            payload.getIdentity(),
                            payload.getAttempt(),
                            payload.getBusiness()
                    );

            tradeService.onOrderExecuted(
                    payload,
                    context
            );
        }

        /*
         * Marker ставится только после успешной
         * обработки TradeService.
         *
         * Если downstream упадёт,
         * transaction откатится и event останется
         * доступным для retry.
         */
        idempotencyService.markAsProcessed(
                eventId,
                CONSUMER_NAME
        );
    }

    private boolean isTradeCreationRequired(
            OrderExecutedEvent payload
    ) {

        if (payload.getStatus() != OrderStatus.FILLED
                && payload.getStatus() != OrderStatus.PARTIALLY_FILLED) {

            return false;
        }

        BigDecimal quantity =
                payload.getQuantity();

        BigDecimal price =
                payload.getPrice();

        return quantity != null
                && quantity.compareTo(
                BigDecimal.ZERO
        ) > 0
                && price != null
                && price.compareTo(
                BigDecimal.ZERO
        ) > 0
                && payload.getExchangeTradeId() != null
                && !payload.getExchangeTradeId().isBlank();
    }
}