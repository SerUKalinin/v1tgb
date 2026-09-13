package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.mapper.TradeMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Сервис обработки торговых сделок.
 * <p>
 * Отвечает за:
 * <ul>
 *     <li>обработку события исполнения ордера</li>
 *     <li>создание сущности Trade</li>
 *     <li>публикацию outbox событий (ORDER_FILLED, TRADE_CREATED)</li>
 *     <li>уведомление RiskEngine о факте сделки</li>
 *     <li>предоставление истории торгов</li>
 * </ul>
 * <p>
 * Является частью execution pipeline и не содержит бизнес-логики риск-менеджмента
 * или построения позиций (делегируется downstream компонентам).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeService {

    private final TradeRepository tradeRepository;
    private final TradeMapper tradeMapper;
    private final OutboxService outboxService;
    private final OrderRepository orderRepository;
    private final RiskEngine riskEngine;

    /**
     * Обрабатывает факт исполнения ордера и создаёт торговую сделку.
     *
     * @param event событие исполнения ордера
     */
    @Transactional
    public void onOrderFilled(OrderFilledEvent event) {
        log.info("[TRADE-SERVICE] Handling order fill for order: {}", event.getOrderId());

        ExecutionContext context = ExecutionContext.of(
                event.getIdentity(),
                event.getAttempt(),
                event.getBusiness()
        );

        // 1. Outbox: ORDER_FILLED
        outboxService.publishEvent(
                context,
                "ORDER",
                "ORDER_FILLED",
                event
        );

        if (tradeRepository.existsByExchangeTradeId(event.getExternalExecutionId())) {
            log.warn("[TRADE-SERVICE] Duplicate trade detected: {}. Skipping.", event.getExternalExecutionId());
            return;
        }

        var order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + event.getOrderId()));

        // 2. Создание Trade сущности
        TradeEntity entity = new TradeEntity();
        entity.setId(IdentityFactory.deriveEventId(context.attempt().executionId(), "trade"));
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

        // 3. Outbox: TRADE_CREATED
        ExecutionContext tradeContext = context.withNextStep(
                IdentityFactory.deriveEventId(context.attempt().executionId(), "trade-publish")
        );

        TradeCreatedEvent tradeCreatedEvent = new TradeCreatedEvent(
                tradeContext.identity(),
                tradeContext.attempt(),
                tradeContext.business(),
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

        outboxService.publishEvent(
                tradeContext,
                "TRADE",
                "TRADE_CREATED",
                tradeCreatedEvent
        );

        // 4. Уведомление RiskEngine
        riskEngine.publish(new RiskEvent.TradeExecuted(
                saved.getExchangeTradeId(),
                saved.getSymbol(),
                saved.getQuantity(),
                saved.getPrice(),
                saved.getRealizedPnl(),
                saved.getExecutedAt()
        ));

        // 5. Projection updates выполняются асинхронно через Outbox consumers
    }

    /**
     * Получает историю сделок по символу и стратегии.
     *
     * @param symbol торговый инструмент
     * @param strategyId идентификатор стратегии
     * @return список сделок в хронологическом порядке
     */
    public List<Trade> getTradeHistory(String symbol, String strategyId) {
        return tradeRepository.findBySymbolAndStrategyIdOrderByExecutedAtAsc(symbol, strategyId)
                .stream()
                .map(tradeMapper::toDomain)
                .toList();
    }

    /**
     * Возвращает все сделки системы.
     *
     * @return список всех сделок, отсортированных по времени исполнения
     */
    public List<Trade> getAllTrades() {
        return tradeRepository.findAllByOrderByExecutedAtAsc()
                .stream()
                .map(tradeMapper::toDomain)
                .toList();
    }
}