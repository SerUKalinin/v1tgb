package com.tradingbot.infrastructure.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.infrastructure.outbox.OutboxConsumer;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbox-обработчик события TRADE_CREATED для обновления проекции позиций.
 *
 * <p>Отвечает за прямое обновление состояния позиций пользователя
 * на основе совершённых сделок.</p>
 *
 * <p>Использует outbox-паттерн для асинхронной обработки событий
 * и обеспечения eventual consistency между доменной моделью и проекцией позиций.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionProjectionHandler implements OutboxConsumer {

    private final PositionService positionService;
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
     * <p>Десериализует событие и передаёт его в PositionService
     * для обновления проекции позиций.</p>
     *
     * @param event запись outbox-таблицы
     * @throws Exception при ошибке десериализации или обработки события
     */
    @Override
    public void consume(OutboxEventEntity event) throws Exception {
        log.info("[POSITION-HANDLER] Consuming TRADE_CREATED for aggregate {}", event.getAggregateId());

        TradeCreatedEvent tradeEvent = objectMapper.readValue(event.getPayload(), TradeCreatedEvent.class);

        // Прямое обновление проекции позиций
        positionService.updatePosition(tradeEvent);
    }
}