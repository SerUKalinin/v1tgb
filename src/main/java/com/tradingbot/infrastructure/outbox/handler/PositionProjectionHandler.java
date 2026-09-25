package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.event.TradeUpdatedEvent;
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
public class PositionProjectionHandler
        implements OutboxConsumer {

    private final PositionService positionService;
    private final ObjectMapper objectMapper;

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

        UUID eventId =
                event.eventId();

        if (eventId == null) {
            throw new IllegalStateException(
                    "TRADE projection event has no eventId"
            );
        }

        if ("TRADE_CREATED".equals(
                event.eventType()
        )) {

            TradeCreatedEvent tradeEvent =
                    objectMapper.readValue(
                            event.payload(),
                            TradeCreatedEvent.class
                    );

            positionService.updatePosition(
                    eventId,
                    tradeEvent
            );

            return;
        }

        TradeUpdatedEvent updateEvent =
                objectMapper.readValue(
                        event.payload(),
                        TradeUpdatedEvent.class
                );

        TradeCreatedEvent deltaProjection =
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

        log.info(
                "[POSITION-HANDLER] Applying cumulative trade delta. " +
                        "eventId={}, tradeId={}, deltaQty={}, cumulativeQty={}, incrementalPrice={}",
                eventId,
                updateEvent.getTradeId(),
                updateEvent.getDeltaQuantity(),
                updateEvent.getCumulativeQuantity(),
                updateEvent.getIncrementalPrice()
        );

        positionService.updatePosition(
                eventId,
                deltaProjection
        );
    }
}