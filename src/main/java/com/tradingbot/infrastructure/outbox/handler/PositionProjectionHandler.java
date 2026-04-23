package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PositionProjectionHandler implements OutboxConsumer {

    private final PositionService positionService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String eventType) {
        return "TRADE_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        log.info("[POSITION-HANDLER] Consuming TRADE_CREATED for aggregate {}", event.getAggregateId());
        
        TradeCreatedEvent tradeEvent = objectMapper.readValue(event.getPayload(), TradeCreatedEvent.class);
        
        // Прямое обновление проекции позиций
        positionService.updatePosition(tradeEvent);
    }
}
