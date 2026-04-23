package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.risk.ApprovedOrder;
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

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxRepository;
    private final RiskEngine riskEngine;
    private final RiskManager riskManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public void placeOrder(SignalEvent signal) {

        // 1. Risk approval (The ONLY way to get a valid Order ID)
        ApprovedOrder approved = riskManager.approveSignal(signal)
                .orElseThrow(() ->
                        new IllegalStateException("Signal rejected by risk")
                );

        // 2. Mandatory Transactional Gate: Reserve capital
        riskEngine.reserve(
                approved.getOrderId(),
                approved.getQuantity().multiply(approved.getPrice())
        );

        // 3. Create Order (Atomic Intent)
        OrderEntity order = createFromApproved(approved);

        orderRepository.save(order);

        // 4. Outbox (same transaction)
        saveOutbox(order);

        log.info("[ORDER-APP] Atomic intent committed: {}", order.getId());
    }

    private OrderEntity createFromApproved(ApprovedOrder approved) {
        return OrderEntity.builder()
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
    }

    private void saveOutbox(OrderEntity order) {        try {
            OutboxPayload payload = new OutboxPayload(
                    order.getId(),
                    order.getClientOrderId(),
                    order.getSymbol(),
                    order.getStatus(),
                    order.getCreatedAt()
            );

            OutboxEventEntity event = OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateId(order.getId())
                    .aggregateType("ORDER")
                    .eventType("ORDER_CREATED")
                    .payload(objectMapper.writeValueAsString(payload))
                    .createdAt(Instant.now())
                    .build();

            outboxRepository.save(event);

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox serialization failed", e);
        }
    }

    record OutboxPayload(
            UUID orderId,
            String clientOrderId,
            String symbol,
            String status,
            Instant createdAt
    ) {}
}