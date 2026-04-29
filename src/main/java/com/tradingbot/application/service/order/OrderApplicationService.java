package com.tradingbot.application.service.order;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderApplicationService {

    private final RiskEngine riskEngine;
    private final RiskManager riskManager;
    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxRepository;
    private final SystemStateManager stateManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public void onSignalReceived(SignalEvent signal) {
        processSignal(signal);
    }

    @Transactional
    public void processSignal(SignalEvent signal) {        if (stateManager.getState() != SystemStateManager.SystemState.TRADING_ENABLED) {
            log.warn("[ORDER-APP] Trading is not enabled (current state: {}). Ignoring signal for {}", 
                    stateManager.getState(), signal.getSymbol());
            return;
        }


        // 1. Валидация (Read-only, без внешних вызовов внутри транзакции, если RiskManager локален)
        ApprovedOrder approved = riskManager.approveSignal(signal)
                .orElseThrow(() -> new IllegalStateException("Signal rejected by risk"));
        // 2. Резервирование капитала (Включает проверки лимитов и запись RiskEvent)
        RiskDecision decision = riskEngine.reserve(
                approved.getOrderId(),
                approved.getQuantity().multiply(approved.getPrice())
        );

        if (!decision.isApproved()) {
            log.warn("[FINANCIAL-CORE] Capital reservation failed for order {}: {} - {}", 
                    approved.getOrderId(), decision.getReason(), decision.getMessage());
            throw new IllegalStateException("Risk reservation failed: " + decision.getReason());
        }

        // 3. Создание ордера
        OrderEntity order = OrderEntity.builder()
                .id(approved.getOrderId())
                .clientOrderId(approved.getClientOrderId())
                .symbol(approved.getSymbol())
                .strategyId(approved.getStrategyId())
                .side(approved.getSide())
                .type(approved.getType())
                .quantity(approved.getQuantity())
                .price(approved.getPrice())
                .status(OrderStatus.PENDING_EXECUTION.name())
                .createdAt(Instant.now())
                .build();
        
        orderRepository.save(order);

        // 4. Запись в Outbox (Атомарно с OrderEntity и RiskEvent внутри publish)
        saveOutbox(order.getId(), "ORDER", "ORDER_CREATED", order);

        log.info("[FINANCIAL-CORE] Atomic transaction committed for order: {} (clientOrderId: {}). Risk trace: {}", 
                order.getId(), order.getClientOrderId(), decision.getTrace());
    }

    @Deprecated
    private String generateClientOrderId(UUID orderId) {
        return com.tradingbot.common.util.ClientOrderIdGenerator.generate(orderId);
    }

    private void saveOutbox(UUID aggregateId, String aggregateType, String eventType, Object payload) {
        try {
            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(aggregateId != null ? aggregateId : UUID.randomUUID())
                    .aggregateType(aggregateType)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(com.tradingbot.infrastructure.outbox.OutboxStatus.NEW)
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Critical: Outbox serialization failed for " + aggregateType, e);
        }
    }

    @Deprecated
    private void saveOutbox(String strategyId, String aggregateType, String eventType, Object payload) {
        saveOutbox((UUID) null, aggregateType, eventType, payload);
    }

    record OutboxPayload(
            UUID orderId,
            String clientOrderId,
            String symbol,
            String status,
            Instant createdAt
    ) {}
}