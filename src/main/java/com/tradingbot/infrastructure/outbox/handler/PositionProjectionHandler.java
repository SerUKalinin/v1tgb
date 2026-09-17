package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PositionProjectionHandler implements OutboxConsumer {

    private final PositionService positionService;

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String eventType) {

        return "TRADE_CREATED".equals(eventType);
    }

    @Override
    public void consume(
            OutboxEvent event
    ) throws Exception {

        UUID eventId =
                event.eventId();

        if (eventId == null) {

            throw new IllegalStateException(
                    "TRADE_CREATED outbox event has no eventId"
            );
        }

        TradeCreatedEvent tradeEvent =
                objectMapper.readValue(
                        event.payload(),
                        TradeCreatedEvent.class
                );

        log.info(
                "[POSITION-HANDLER] Consuming TRADE_CREATED. " +
                        "eventId={}, aggregateId={}, tradeId={}",
                eventId,
                event.aggregateId(),
                tradeEvent.getTradeId()
        );

        positionService.updatePosition(
                eventId,
                tradeEvent
        );
    }
}