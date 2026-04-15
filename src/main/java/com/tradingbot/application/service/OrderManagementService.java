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

        // В Stage 3 мы адаптируем OrderRequest к SignalEvent, чтобы пройти через единый Gate
        SignalEvent signal = SignalEvent.builder()
                .symbol(request.getSymbol())
                .type(request.getSide() == com.tradingbot.common.enums.OrderSide.BUY ? SignalType.BUY : SignalType.SELL)
                .price(request.getPrice())
                .strategyId(request.getStrategyId())
                .candleTime(Instant.now())
                .build();

        return processSignal(signal);
    }

    private ExecutionResult processSignal(SignalEvent signal) {
        // 1. Risk Enforcement Gate (Centralized Sizing & Validation)
        java.util.Optional<com.tradingbot.domain.risk.ApprovedOrder> approvedOrderOpt = riskManager.approveSignal(signal);
        
        if (approvedOrderOpt.isEmpty()) {
            log.warn("[OMS] Signal rejected by Risk Gate: {}", signal);
            return ExecutionResult.failure(null, "Rejected by Risk Gate");
        }

        com.tradingbot.domain.risk.ApprovedOrder approvedOrder = approvedOrderOpt.get();

        // 2. Persistence (OrderEntity)
        OrderEntity order = OrderEntity.builder()
                .id(approvedOrder.getOrderId())
                .clientOrderId(approvedOrder.getClientOrderId())
                .symbol(approvedOrder.getSymbol())
                .strategyId(approvedOrder.getStrategyId())
                .side(approvedOrder.getSide())
                .type(approvedOrder.getType())
                .quantity(approvedOrder.getQuantity())
                .price(approvedOrder.getPrice())
                .status(OrderStatus.VALIDATED.name())
                .createdAt(Instant.now())
                .build();
        
        order = orderRepository.save(order);
        publishOrderEvent(order, "Approved by Risk Gate");

        // 3. Execution
        return executeInternal(order, approvedOrder);
    }

    private ExecutionResult executeInternal(OrderEntity order, com.tradingbot.domain.risk.ApprovedOrder approvedOrder) {
        try {
            ExecutionResult result = executionEngine.execute(approvedOrder);
            
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

        String dedupeId = event.getSymbol() + ":" + (event.getCandleTime() != null ? event.getCandleTime().toEpochMilli() : System.currentTimeMillis());
        if (processedSignals.putIfAbsent(dedupeId, Boolean.TRUE) != null) {
            log.debug("[OMS] Duplicate signal detected for {}, skipping", dedupeId);
            return;
        }

        log.info("[OMS] Processing signal: {} {} @ {}", event.getType(), event.getSymbol(), event.getPrice());
        processSignal(event);
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