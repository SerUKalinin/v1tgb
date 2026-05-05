package com.tradingbot.application.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.mapper.TradeMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final TradeRepository tradeRepository;
    private final TradeMapper tradeMapper;
    private final EquityService equityService;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final RiskEngine riskEngine;

    /**
     * Регистрирует сделки и уведомляет зависимые сервисы через Outbox.
     */
    @Transactional
    public void onOrderFilled(OrderFilledEvent event) {
        log.info("[TRADE-SERVICE] Handling order fill for order: {}", event.getOrderId());

        // 1. Outbox: ORDER_FILLED (Фиксация факта исполнения)
        saveOutbox(event.getOrderId(), "ORDER", "ORDER_FILLED", event);

        if (tradeRepository.existsByExchangeTradeId(event.getExternalExecutionId())) {
            log.warn("[TRADE-SERVICE] Duplicate trade detected: {}. Skipping.", event.getExternalExecutionId());
            return;
        }

        var order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + event.getOrderId()));

        // 2. Создание сущности сделки
        TradeEntity entity = new TradeEntity();
        entity.setOrder(order);
        entity.setClientOrderId(order.getClientOrderId());
        entity.setExchangeTradeId(event.getExternalExecutionId());
        entity.setSymbol(event.getSymbol());
        entity.setQuantity(event.getQuantity());
        entity.setPrice(event.getPrice());
        entity.setSide(order.getSide());
        entity.setStrategyId(order.getStrategyId());
        entity.setExecutedAt(Instant.now());
        entity.setRealizedPnl(java.math.BigDecimal.ZERO);

        TradeEntity saved = tradeRepository.save(entity);

        // 3. Outbox: TRADE_CREATED (Для асинхронных проекций, например PositionService)
        saveOutbox(saved.getId(), "TRADE", "TRADE_CREATED", saved);

        // 4. Обновление RiskEngine (внутренний Event Sourcing)
        riskEngine.publish(new RiskEvent.TradeExecuted(
                saved.getExchangeTradeId(),
                saved.getSymbol(),
                saved.getQuantity(),
                saved.getPrice(),
                saved.getRealizedPnl(),
                saved.getExecutedAt()
        ));

        // 5. Синхронное уведомление Equity (если требуется немедленный пересчет баланса)
        TradeCreatedEvent tradeCreatedEvent = new TradeCreatedEvent(
                saved.getId(),
                saved.getOrder().getId(),
                saved.getSymbol(),
                saved.getStrategyId(),
                saved.getQuantity(),
                saved.getPrice(),
                saved.getSide(),
                order.getStopLoss(),
                order.getTakeProfit()
        );
        equityService.onTradeCreated(tradeCreatedEvent);
    }

    private void saveOutbox(UUID aggregateId, String aggregateType, String eventType, Object payload) {
        try {
            OutboxEventEntity outboxEvent = OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(aggregateId)
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.NEW)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox serialization failed", e);
        }
    }

    public List<Trade> getTradeHistory(String symbol, String strategyId) {
        return tradeRepository.findBySymbolAndStrategyIdOrderByExecutedAtAsc(symbol, strategyId)
                .stream()
                .map(tradeMapper::toDomain)
                .toList();
    }

    public List<Trade> getAllTrades() {
        return tradeRepository.findAllByOrderByExecutedAtAsc()
                .stream()
                .map(tradeMapper::toDomain)
                .toList();
    }
}
