package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderPort;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.model.TradePort;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Application service обработки торговых сделок.
 *
 * <p>
 * Persistence детали полностью скрыты за domain/application ports.
 *
 * <p>
 * Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeService {

    private final TradePort tradePort;
    private final OrderPort orderPort;
    private final OutboxService outboxService;
    private final RiskEngine riskEngine;

    /**
     * Обрабатывает факт исполнения ордера и создаёт Trade.
     *
     * @param event событие исполнения
     */
    @Transactional
    public void onOrderFilled(OrderFilledEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }

        log.info(
                "[TRADE-SERVICE] Handling order fill for order: {}",
                event.getOrderId()
        );

        ExecutionContext context = ExecutionContext.of(
                event.getIdentity(),
                event.getAttempt(),
                event.getBusiness()
        );

        /*
         * ORDER_FILLED публикуется до проверки duplicate trade
         * в соответствии с текущим поведением системы.
         */
        outboxService.publishEvent(
                context,
                "ORDER",
                "ORDER_FILLED",
                event
        );

        if (tradePort.existsByExchangeTradeId(
                event.getExternalExecutionId()
        )) {
            log.warn(
                    "[TRADE-SERVICE] Duplicate trade detected: {}. Skipping.",
                    event.getExternalExecutionId()
            );
            return;
        }

        Order order = orderPort
                .findById(event.getOrderId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Order not found: " + event.getOrderId()
                        )
                );

        Trade trade = buildTrade(
                event.getExternalExecutionId(),
                event.getQuantity(),
                event.getPrice(),
                order,
                context
        );

        Trade saved = tradePort.save(trade);

        publishTradeCreated(saved, order, context);

        publishRiskEvent(saved);
    }

    /**
     * Получает историю сделок по символу и стратегии.
     */
    public List<Trade> getTradeHistory(
            String symbol,
            String strategyId
    ) {
        return tradePort.findBySymbolAndStrategyId(
                symbol,
                strategyId
        );
    }

    /**
     * Возвращает всю торговую историю.
     */
    public List<Trade> getAllTrades() {
        return tradePort.findAll();
    }

    /**
     * Обрабатывает ORDER_EXECUTED.
     */
    @Transactional
    public void onOrderExecuted(
            OrderExecutedEvent event,
            ExecutionContext context
    ) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }

        if (context == null) {
            throw new IllegalArgumentException(
                    "ExecutionContext cannot be null"
            );
        }

        log.info(
                "[TRADE-SERVICE] Creating trade from ORDER_EXECUTED " +
                        "for order: {} exchangeTradeId: {}",
                event.getOrderId(),
                event.getExchangeTradeId()
        );

        if (tradePort.existsByExchangeTradeId(
                event.getExchangeTradeId()
        )) {
            log.warn(
                    "[TRADE-SERVICE] Duplicate trade detected: {}. Skipping.",
                    event.getExchangeTradeId()
            );
            return;
        }

        Order order = orderPort
                .findById(event.getOrderId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Order not found: " + event.getOrderId()
                        )
                );

        Trade trade = buildTrade(
                event.getExchangeTradeId(),
                event.getQuantity(),
                event.getPrice(),
                order,
                context
        );

        Trade saved = tradePort.save(trade);

        publishTradeCreated(saved, order, context);

        publishRiskEvent(saved);

        log.info(
                "[TRADE-SERVICE] Trade created successfully. " +
                        "tradeId={}, orderId={}, exchangeTradeId={}",
                saved.getId(),
                saved.getOrderId(),
                saved.getExchangeTradeId()
        );
    }

    private Trade buildTrade(
            String exchangeTradeId,
            BigDecimal quantity,
            BigDecimal price,
            Order order,
            ExecutionContext context
    ) {
        if (exchangeTradeId == null) {
            throw new IllegalArgumentException(
                    "exchangeTradeId cannot be null"
            );
        }

        if (quantity == null) {
            throw new IllegalArgumentException(
                    "quantity cannot be null"
            );
        }

        if (price == null) {
            throw new IllegalArgumentException(
                    "price cannot be null"
            );
        }

        return Trade.builder()
                .id(
                        IdentityFactory.deriveEventId(
                                context.attempt().executionId(),
                                "trade"
                        )
                )
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .exchangeTradeId(exchangeTradeId)
                .symbol(order.getSymbol())
                .strategyId(order.getStrategyId())
                .side(order.getSide())
                .quantity(quantity)
                .price(price)
                .feeAmount(BigDecimal.ZERO)
                .feeAsset(null)
                .realizedPnl(BigDecimal.ZERO)
                .executedAt(Instant.now())
                .build();
    }

    private void publishTradeCreated(
            Trade trade,
            Order order,
            ExecutionContext context
    ) {
        ExecutionContext tradeContext =
                context.withNextStep(
                        IdentityFactory.deriveEventId(
                                context.attempt().executionId(),
                                "trade-publish"
                        )
                );

        TradeCreatedEvent tradeCreatedEvent =
                new TradeCreatedEvent(
                        tradeContext.identity(),
                        tradeContext.attempt(),
                        tradeContext.business(),
                        trade.getId(),
                        trade.getOrderId(),
                        trade.getSymbol(),
                        trade.getStrategyId(),
                        trade.getQuantity(),
                        trade.getPrice(),
                        trade.getSide(),
                        null,
                        null
                );

        outboxService.publishEvent(
                tradeContext,
                "TRADE",
                "TRADE_CREATED",
                tradeCreatedEvent
        );
    }

    private void publishRiskEvent(Trade trade) {
        riskEngine.publish(
                new RiskEvent.TradeExecuted(
                        trade.getExchangeTradeId(),
                        trade.getSymbol(),
                        trade.getQuantity(),
                        trade.getPrice(),
                        trade.getRealizedPnl(),
                        trade.getExecutedAt()
                )
        );
    }
}