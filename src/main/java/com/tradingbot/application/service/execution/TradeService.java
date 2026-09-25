package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.event.OrderExecutedEvent;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.event.TradeUpdatedEvent;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeService {

    private final TradePort tradePort;
    private final OrderPort orderPort;
    private final OutboxService outboxService;
    private final RiskEngine riskEngine;

    @Transactional
    public void onOrderFilled(
            OrderFilledEvent event
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "event cannot be null"
            );
        }

        log.info(
                "[TRADE-SERVICE] Handling order fill for order: {}",
                event.getOrderId()
        );

        ExecutionContext context =
                ExecutionContext.of(
                        event.getIdentity(),
                        event.getAttempt(),
                        event.getBusiness()
                );

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

        Order order =
                orderPort
                        .findById(
                                event.getOrderId()
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Order not found: "
                                                + event.getOrderId()
                                )
                        );

        Trade trade =
                buildTrade(
                        event.getExternalExecutionId(),
                        event.getQuantity(),
                        event.getPrice(),
                        order,
                        context
                );

        Trade saved =
                tradePort.save(
                        trade
                );

        publishTradeCreated(
                saved,
                order,
                context
        );

        publishRiskEvent(
                saved
        );
    }

    public List<Trade> getTradeHistory(
            String symbol,
            String strategyId
    ) {

        return tradePort
                .findBySymbolAndStrategyId(
                        symbol,
                        strategyId
                );
    }

    public List<Trade> getAllTrades() {
        return tradePort.findAll();
    }

    /**
     * ORDER_EXECUTED is cumulative.
     *
     * One Order / executionId owns one Trade row.
     *
     * First checkpoint:
     *
     * 0.3
     *   -> Trade CREATE
     *
     * Later checkpoint:
     *
     * 0.6
     *   -> same Trade row updated
     *   -> downstream receives delta 0.3
     *
     * Duplicate checkpoint:
     *
     * 0.6
     *   -> no-op
     */
    @Transactional
    public void onOrderExecuted(
            OrderExecutedEvent event,
            ExecutionContext context
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "event cannot be null"
            );
        }

        if (context == null) {
            throw new IllegalArgumentException(
                    "ExecutionContext cannot be null"
            );
        }

        log.info(
                "[TRADE-SERVICE] Processing ORDER_EXECUTED " +
                        "orderId={} exchangeTradeId={} qty={} price={}",
                event.getOrderId(),
                event.getExchangeTradeId(),
                event.getQuantity(),
                event.getPrice()
        );

        if (event.getQuantity() == null
                || event.getQuantity().signum() <= 0) {

            throw new IllegalArgumentException(
                    "ORDER_EXECUTED quantity must be positive"
            );
        }

        if (event.getPrice() == null
                || event.getPrice().signum() <= 0) {

            throw new IllegalArgumentException(
                    "ORDER_EXECUTED price must be positive"
            );
        }

        Order order =
                orderPort
                        .findById(
                                event.getOrderId()
                        )
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Order not found: "
                                                + event.getOrderId()
                                )
                        );

        Trade existingTrade =
                tradePort
                        .findByOrderId(
                                event.getOrderId()
                        )
                        .orElse(null);

        /*
         * FIRST cumulative checkpoint.
         */
        if (existingTrade == null) {

            Trade trade =
                    buildTrade(
                            event.getExchangeTradeId(),
                            event.getQuantity(),
                            event.getPrice(),
                            order,
                            context
                    );

            Trade saved =
                    tradePort.save(
                            trade
                    );

            TradeCreatedEvent createdEvent =
                    publishTradeCreated(
                            saved,
                            order,
                            context
                    );

            publishRiskEvent(
                    createdEvent.getEventId(),
                    saved.getSymbol(),
                    saved.getQuantity(),
                    saved.getPrice(),
                    saved.getRealizedPnl(),
                    saved.getExecutedAt()
            );

            log.info(
                    "[TRADE-SERVICE] Trade created successfully. " +
                            "tradeId={}, orderId={}, quantity={}",
                    saved.getId(),
                    saved.getOrderId(),
                    saved.getQuantity()
            );

            return;
        }

        BigDecimal previousQuantity =
                valueOrZero(
                        existingTrade.getQuantity()
                );

        BigDecimal incomingQuantity =
                event.getQuantity();

        /*
         * Same or older cumulative state is a no-op.
         */
        if (incomingQuantity.compareTo(
                previousQuantity
        ) <= 0) {

            log.info(
                    "[TRADE-SERVICE] Cumulative checkpoint already applied. " +
                            "orderId={}, existingQty={}, incomingQty={}",
                    event.getOrderId(),
                    previousQuantity,
                    incomingQuantity
            );

            return;
        }

        BigDecimal previousPrice =
                existingTrade.getPrice();

        if (previousPrice == null
                || previousPrice.signum() <= 0) {

            throw new IllegalStateException(
                    "Existing Trade has invalid price: "
                            + existingTrade.getId()
            );
        }

        /*
         * Cumulative notional:
         *
         * previous = q1 * p1
         * current  = q2 * p2
         *
         * deltaNotional = current - previous
         */
        BigDecimal previousNotional =
                previousQuantity
                        .multiply(
                                previousPrice
                        );

        BigDecimal currentNotional =
                incomingQuantity
                        .multiply(
                                event.getPrice()
                        );

        BigDecimal deltaNotional =
                currentNotional
                        .subtract(
                                previousNotional
                        );

        if (deltaNotional.signum() <= 0) {

            throw new IllegalStateException(
                    "Cumulative execution notional must increase. " +
                            "orderId=" + event.getOrderId() +
                            ", previous=" + previousNotional +
                            ", current=" + currentNotional
            );
        }

        BigDecimal deltaQuantity =
                incomingQuantity
                        .subtract(
                                previousQuantity
                        );

        BigDecimal incrementalPrice =
                deltaNotional.divide(
                        deltaQuantity,
                        8,
                        RoundingMode.HALF_UP
                );

        /*
         * One lifecycle -> one Trade.
         *
         * Trade stores authoritative cumulative quantity/price.
         */
        Trade updatedTrade =
                Trade.builder()
                        .id(existingTrade.getId())
                        .orderId(existingTrade.getOrderId())
                        .clientOrderId(
                                existingTrade.getClientOrderId()
                        )
                        .exchangeTradeId(
                                event.getExchangeTradeId()
                        )
                        .symbol(existingTrade.getSymbol())
                        .strategyId(existingTrade.getStrategyId())
                        .side(existingTrade.getSide())
                        .quantity(incomingQuantity)
                        .price(event.getPrice())
                        .feeAmount(
                                existingTrade.getFeeAmount()
                        )
                        .feeAsset(
                                existingTrade.getFeeAsset()
                        )
                        .realizedPnl(
                                existingTrade.getRealizedPnl()
                        )
                        .executedAt(
                                existingTrade.getExecutedAt()
                        )
                        .build();

        Trade saved =
                tradePort.save(
                        updatedTrade
                );

        TradeUpdatedEvent updatedEvent =
                publishTradeUpdated(
                        saved,
                        order,
                        context,
                        deltaQuantity,
                        incomingQuantity,
                        event.getPrice(),
                        incrementalPrice,
                        event.getExchangeTradeId()
                );

        /*
         * Risk получает только incremental execution.
         *
         * Не cumulative quantity.
         */
        publishRiskEvent(
                updatedEvent.getEventId(),
                saved.getSymbol(),
                deltaQuantity,
                incrementalPrice,
                BigDecimal.ZERO,
                saved.getExecutedAt()
        );

        log.info(
                "[TRADE-SERVICE] Trade cumulative state updated. " +
                        "tradeId={}, previousQty={}, incomingQty={}, deltaQty={}",
                saved.getId(),
                previousQuantity,
                incomingQuantity,
                deltaQuantity
        );
    }

    private Trade buildTrade(
            String exchangeTradeId,
            BigDecimal quantity,
            BigDecimal price,
            Order order,
            ExecutionContext context
    ) {

        if (exchangeTradeId == null
                || exchangeTradeId.isBlank()) {

            throw new IllegalArgumentException(
                    "exchangeTradeId cannot be null or blank"
            );
        }

        if (quantity == null
                || quantity.signum() <= 0) {

            throw new IllegalArgumentException(
                    "quantity must be positive"
            );
        }

        if (price == null
                || price.signum() <= 0) {

            throw new IllegalArgumentException(
                    "price must be positive"
            );
        }

        return Trade.builder()
                .id(
                        IdentityFactory.deriveEventId(
                                context.attempt().executionId(),
                                "trade"
                        )
                )
                .orderId(
                        order.getId()
                )
                .clientOrderId(
                        order.getClientOrderId()
                )
                .exchangeTradeId(
                        exchangeTradeId
                )
                .symbol(
                        order.getSymbol()
                )
                .strategyId(
                        order.getStrategyId()
                )
                .side(
                        order.getSide()
                )
                .quantity(
                        quantity
                )
                .price(
                        price
                )
                .feeAmount(
                        BigDecimal.ZERO
                )
                .feeAsset(
                        null
                )
                .realizedPnl(
                        BigDecimal.ZERO
                )
                .executedAt(
                        Instant.now()
                )
                .build();
    }

    private TradeCreatedEvent publishTradeCreated(
            Trade trade,
            Order order,
            ExecutionContext context
    ) {

        ExecutionContext tradeContext =
                context.withNextStep(
                        IdentityFactory.deriveEventId(
                                context.attempt().executionId(),
                                "TRADE_CREATED"
                        )
                );

        TradeCreatedEvent event =
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
                event.getEventType(),
                event
        );

        return event;
    }

    private TradeUpdatedEvent publishTradeUpdated(
            Trade trade,
            Order order,
            ExecutionContext context,
            BigDecimal deltaQuantity,
            BigDecimal cumulativeQuantity,
            BigDecimal cumulativePrice,
            BigDecimal incrementalPrice,
            String exchangeTradeId
    ) {

        String eventType =
                TradeUpdatedEvent.eventTypeFor(
                        context.attempt().executionId(),
                        cumulativeQuantity,
                        cumulativePrice
                );

        ExecutionContext tradeContext =
                context.withNextStep(
                        IdentityFactory.deriveEventId(
                                context.attempt().executionId(),
                                eventType
                        )
                );

        TradeUpdatedEvent event =
                new TradeUpdatedEvent(
                        tradeContext.identity(),
                        tradeContext.attempt(),
                        tradeContext.business(),
                        eventType,
                        trade.getId(),
                        trade.getOrderId(),
                        trade.getSymbol(),
                        trade.getStrategyId(),
                        deltaQuantity,
                        cumulativeQuantity,
                        cumulativePrice,
                        incrementalPrice,
                        trade.getSide(),
                        exchangeTradeId
                );

        outboxService.publishEvent(
                tradeContext,
                "TRADE",
                event.getEventType(),
                event
        );

        return event;
    }

    private void publishRiskEvent(
            Trade trade
    ) {

        publishRiskEvent(
                trade.getExchangeTradeId(),
                trade.getSymbol(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getRealizedPnl(),
                trade.getExecutedAt()
        );
    }

    private void publishRiskEvent(
            java.util.UUID eventId,
            String symbol,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal realizedPnl,
            Instant executedAt
    ) {

        publishRiskEvent(
                eventId.toString(),
                symbol,
                quantity,
                price,
                realizedPnl,
                executedAt
        );
    }

    private void publishRiskEvent(
            String eventId,
            String symbol,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal realizedPnl,
            Instant executedAt
    ) {

        riskEngine.publish(
                new RiskEvent.TradeExecuted(
                        eventId,
                        symbol,
                        quantity,
                        price,
                        realizedPnl,
                        executedAt
                )
        );
    }

    private BigDecimal valueOrZero(
            BigDecimal value
    ) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }
}