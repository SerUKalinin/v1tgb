package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.OrderEvent;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.event.TradeExecutedEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderManagementService {

    private final OrderRepository orderRepository;
    private final TradeService tradeService;
    private final PositionService positionService;
    private final RiskManager riskManager;
    private final ExecutionEngine executionEngine;
    private final ApplicationEventPublisher eventPublisher;
    // Простая дедупликация в памяти для MVP (symbol + candleTime)
    private final Map<String, Boolean> processedSignals = new ConcurrentHashMap<>();

    /**
     * Публичный API для исполнения ордера.
     * Принимает OrderRequest, создает OrderEntity, проходит проверки риска и исполняет.
     */
    @Transactional
    public ExecutionResult executeOrder(OrderRequest request) {
        log.info("[OMS] Public API call: executeOrder for {} {}", request.getSide(), request.getSymbol());

        // 1. Создание ордера (NEW)
        OrderEntity order = OrderEntity.builder()
                .id(UUID.randomUUID().toString())
                .clientOrderId(request.getClientOrderId() != null ? request.getClientOrderId() : "c-" + UUID.randomUUID().toString().substring(0, 8))
                .symbol(request.getSymbol())
                .strategyId(request.getStrategyId())
                .side(request.getSide())
                .type(request.getType() != null ? request.getType() : com.tradingbot.common.enums.OrderType.MARKET)
                .quantity(request.getAmount())
                .price(request.getPrice())
                .status(OrderStatus.NEW.name())
                .createdAt(Instant.now())
                .build();        order = orderRepository.save(order);
        publishOrderEvent(order, "Order created via API");

        // 2. Risk Check
        RiskDecision decision = riskManager.check(order);
        if (!decision.isApproved()) {
            order.setStatus(OrderStatus.REJECTED.name());
            orderRepository.save(order);
            publishOrderEvent(order, "Rejected by risk: " + decision.getReason());
            return ExecutionResult.failure(order.getId(), "Risk check failed: " + decision.getReason());
        }

        if (decision.getType() == RiskDecision.DecisionType.REDUCE_SIZE) {
            order.setQuantity(decision.getAmount());
            log.info("[OMS] Order size reduced by risk to {}", decision.getAmount());
        }

        order.setStatus(OrderStatus.VALIDATED.name());
        order = orderRepository.save(order);
        publishOrderEvent(order, "Risk check passed");

        // 3. Execution
        return executeInternal(order);
    }

    private ExecutionResult executeInternal(OrderEntity order) {
        try {
            OrderRequest request = OrderRequest.builder()
                    .orderId(order.getId())
                    .clientOrderId(order.getClientOrderId())
                    .symbol(order.getSymbol())
                    .side(order.getSide())
                    .type(order.getType())
                    .amount(order.getQuantity())
                    .price(order.getPrice())
                    .strategyId(order.getStrategyId())
                    .build();
            ExecutionResult result = executionEngine.execute(request);
            
            if (result.isSuccess()) {
                order.setStatus(OrderStatus.FILLED.name());
                order.setExchangeOrderId(result.getExchangeOrderId());
                orderRepository.save(order);
                
                publishOrderEvent(order, "Order filled successfully");
                return result;
            } else {
                order.setStatus(OrderStatus.REJECTED.name());
                orderRepository.save(order);
                publishOrderEvent(order, "Execution failed: " + result.getErrorMessage());
                return result;
            }
        } catch (Exception e) {
            log.error("[OMS] Execution error for order {}", order.getId(), e);
            order.setStatus(OrderStatus.ERROR.name());
            orderRepository.save(order);
            publishOrderEvent(order, "Critical execution error: " + e.getMessage());
            return ExecutionResult.failure(order.getId(), e.getMessage());
        }
    }

    @EventListener
    @Transactional
    public void onSignal(SignalEvent event) {
        if (event.getType() == SignalType.HOLD) return;

        String dedupeId = event.getSymbol() + ":" + event.getCandleTime().toEpochMilli();
        if (processedSignals.putIfAbsent(dedupeId, Boolean.TRUE) != null) {
            log.debug("[OMS] Duplicate signal detected for {}, skipping", dedupeId);
            return;
        }

        log.info("[OMS] Processing signal: {} {} @ {}", event.getType(), event.getSymbol(), event.getPrice());

        OrderRequest request = OrderRequest.builder()
                .clientOrderId("c-" + UUID.randomUUID().toString().substring(0, 8))
                .symbol(event.getSymbol())
                .side(event.getType() == SignalType.BUY ? com.tradingbot.common.enums.OrderSide.BUY : com.tradingbot.common.enums.OrderSide.SELL)
                .amount(event.getQuantity() != null ? event.getQuantity() : new java.math.BigDecimal("0.01"))
                .price(event.getPrice())
                .strategyId(event.getStrategyId())
                .build();
        executeOrder(request);
    }
    @EventListener
    @Transactional
    public void onTradeExecuted(TradeExecutedEvent event) {
        // Метод пустой или может быть удален, так как TradeService теперь слушает OrderFilledEvent
    }

    private void publishOrderEvent(OrderEntity order, String message) {
        eventPublisher.publishEvent(OrderEvent.builder()
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .status(OrderStatus.valueOf(order.getStatus()))
                .symbol(order.getSymbol())
                .side(order.getSide())
                .quantity(order.getQuantity())
                .message(message)
                .timestamp(Instant.now())
                .build());
    }
}