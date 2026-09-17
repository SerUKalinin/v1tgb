package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.OutboxEvent;
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
public class EquityProjectionHandler implements OutboxConsumer {

    private final EquityService equityService;

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String eventType) {

        return "TRADE_CREATED".equals(eventType);
    }

    @Override
    public void consume(
            OutboxEvent event
    ) throws Exception {

        log.info(
                "[EQUITY-HANDLER] Consuming TRADE_CREATED for aggregate {}",
                event.aggregateId()
        );

        TradeCreatedEvent tradeEvent =
                objectMapper.readValue(
                        event.payload(),
                        TradeCreatedEvent.class
                );

        equityService.onTradeCreated(
                tradeEvent
        );
    }
}