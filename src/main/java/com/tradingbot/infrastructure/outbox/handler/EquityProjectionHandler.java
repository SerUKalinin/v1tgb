package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.event.TradeUpdatedEvent;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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

        return "TRADE_CREATED".equals(
                eventType
        )
                || TradeUpdatedEvent.isEventType(
                eventType
        );
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
                    "TRADE projection event has no eventId"
            );
        }

        if (idempotencyService
                .isAlreadyProcessedByConsumer(
                        event.eventId(),
                        CONSUMER_NAME
                )) {

            log.info(
                    "[EQUITY-HANDLER] Event {} already processed. Skipping.",
                    event.eventId()
            );

            return;
        }

        TradeCreatedEvent projectionEvent;

        if ("TRADE_CREATED".equals(
                event.eventType()
        )) {

            projectionEvent =
                    objectMapper.readValue(
                            event.payload(),
                            TradeCreatedEvent.class
                    );

        } else {

            TradeUpdatedEvent updateEvent =
                    objectMapper.readValue(
                            event.payload(),
                            TradeUpdatedEvent.class
                    );

            projectionEvent =
                    new TradeCreatedEvent(
                            updateEvent.getIdentity(),
                            updateEvent.getAttempt(),
                            updateEvent.getBusiness(),
                            updateEvent.getTradeId(),
                            updateEvent.getOrderId(),
                            updateEvent.getSymbol(),
                            updateEvent.getStrategyId(),
                            updateEvent.getDeltaQuantity(),
                            updateEvent.getIncrementalPrice(),
                            updateEvent.getSide(),
                            null,
                            null
                    );
        }

        equityService.onTradeCreated(
                projectionEvent
        );

        idempotencyService
                .markAsProcessedByConsumer(
                        event.eventId(),
                        CONSUMER_NAME
                );
    }
}