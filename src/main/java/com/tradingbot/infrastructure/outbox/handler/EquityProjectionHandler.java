package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbox-обработчик события TRADE_CREATED для обновления equity-проекции.
 *
 * <p>Отвечает за асинхронное обновление агрегированных финансовых метрик
 * (equity snapshot) на основе совершённых сделок.</p>
 *
 * <p>Работает в рамках outbox-pattern и обеспечивает eventual consistency
 * между доменной моделью и проекцией equity.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquityProjectionHandler implements OutboxConsumer {

    private final EquityService equityService;
    private final ObjectMapper objectMapper;

    /**
     * Проверяет, поддерживает ли обработчик данный тип события.
     *
     * @param eventType тип события из outbox
     * @return true если событие TRADE_CREATED
     */
    @Override
    public boolean supports(String eventType) {
        return "TRADE_CREATED".equals(eventType);
    }

    /**
     * Обрабатывает событие TRADE_CREATED из outbox.
     *
     * <p>Десериализует событие и передаёт его в EquityService
     * для обновления состояния equity.</p>
     *
     * @param event запись outbox-таблицы
     * @throws Exception при ошибке десериализации или обработки события
     */
    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        log.info("[EQUITY-HANDLER] Consuming TRADE_CREATED for aggregate {}", event.getAggregateId());

        TradeCreatedEvent tradeEvent = objectMapper.readValue(event.getPayload(), TradeCreatedEvent.class);

        // Асинхронное обновление equity/snapshot
        equityService.onTradeCreated(tradeEvent);
    }
}