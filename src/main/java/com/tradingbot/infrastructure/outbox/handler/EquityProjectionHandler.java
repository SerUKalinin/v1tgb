package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquityProjectionHandler implements OutboxConsumer {

    private final EquityService equityService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String eventType) {
        return "TRADE_CREATED".equals(eventType);
    }

    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        log.info("[EQUITY-HANDLER] Consuming TRADE_CREATED for aggregate {}", event.getAggregateId());

        TradeCreatedEvent tradeEvent = objectMapper.readValue(event.getPayload(), TradeCreatedEvent.class);

        // Асинхронное обновление equity/snapshot
        equityService.onTradeCreated(tradeEvent);
    }
}
