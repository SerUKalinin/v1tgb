package com.tradingbot.application.service.order;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.event.OrderEventPayload;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final RiskManager riskManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final OutboxEventRepository outboxRepository;

    @Transactional
    public void onSignalReceived(SignalEvent signal) {
        createOrder(signal);
    }

    @Transactional
    public void createOrder(SignalEvent signal) {
        // 1. Risk Check
        Optional<ApprovedOrder> approved = riskManager.approveSignal(signal);
        if (approved.isEmpty()) return;

        ApprovedOrder decision = approved.get();

        // 2. Create Domain Order
        Order order = Order.builder()
                .id(decision.getOrderId())
                .clientOrderId(decision.getClientOrderId())
                .symbol(decision.getSymbol())
                .side(decision.getSide())
                .type(decision.getType())
                .originalQuantity(decision.getQuantity())
                .price(decision.getPrice())
                .strategyId(decision.getStrategyId())
                .status(com.tradingbot.common.enums.OrderStatus.PENDING_EXECUTION)
                .executedQuantity(java.math.BigDecimal.ZERO)
                .averagePrice(java.math.BigDecimal.ZERO)
                .build();

        // 3. Save Entity
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);

        // 4. Save Outbox Event using stable DTO
        OrderEventPayload payload = OrderEventPayload.builder()
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .status(order.getStatus().name())
                .timestamp(Instant.now())
                .strategyId(order.getStrategyId())
                .build();

        saveOutbox(order.getId(), "ORDER", "ORDER_CREATED", payload);

        log.info("[FINANCIAL-CORE] Order created and outbox saved: {}", order.getId());
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Critical: Outbox serialization failed for " + aggregateType, e);
        }
    }
}