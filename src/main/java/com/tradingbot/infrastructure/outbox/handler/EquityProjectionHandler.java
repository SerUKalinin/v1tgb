package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Outbox-обработчик события TRADE_CREATED
 * для обновления equity-проекции.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class EquityProjectionHandler
        implements OutboxConsumer {

    private static final String CONSUMER_NAME =
            "EquityProjectionHandler";

    private final EquityService equityService;

    private final ObjectMapper objectMapper;

    private final IdempotencyService idempotencyService;

    @Override
    public boolean supports(
            String eventType
    ) {
        return "TRADE_CREATED".equals(eventType);
    }

    @Override
    public void consume(
            OutboxEvent event
    ) throws Exception {

        if (event == null) {
            throw new IllegalArgumentException(
                    "OutboxEvent cannot be null"
            );
        }

        if (event.eventId() == null) {
            throw new IllegalStateException(
                    "TRADE_CREATED outbox event has no eventId"
            );
        }

        log.info(
                "[EQUITY-HANDLER] Consuming TRADE_CREATED for aggregate {}",
                event.aggregateId()
        );

        if (idempotencyService.isAlreadyProcessedByConsumer(
                event.eventId(),
                CONSUMER_NAME
        )) {

            log.info(
                    "[EQUITY-HANDLER] Event {} already processed by {}. Skipping.",
                    event.eventId(),
                    CONSUMER_NAME
            );

            return;
        }

        TradeCreatedEvent tradeEvent =
                objectMapper.readValue(
                        event.payload(),
                        TradeCreatedEvent.class
                );

        equityService.onTradeCreated(
                tradeEvent
        );

        /*
         * Marker фиксируется только после успешной
         * business mutation.
         *
         * Equity snapshot + idempotency marker
         * находятся в общей transaction boundary
         * OutboxProcessor.processSingleEvent().
         */
        idempotencyService.markAsProcessedByConsumer(
                event.eventId(),
                CONSUMER_NAME
        );
    }
}