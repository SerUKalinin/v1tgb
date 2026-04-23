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

    @Transactional // Единая граница транзакции
    public void onSignalReceived(SignalEvent signal) {
        // 1. Валидация (Read-only, не меняет состояние)
        ApprovedOrder approved = riskManager.approveSignal(signal)
                .orElseThrow(() -> new IllegalStateException("Signal rejected by risk"));

        // 2. Резервирование капитала (Первая запись в БД)
        riskEngine.reserve(
                approved.getOrderId(),
                approved.getQuantity().multiply(approved.getPrice())
        );

        // 3. Создание ордера (Вторая запись в БД)
        OrderEntity order = createFromApproved(approved);
        orderRepository.save(order);

        // 4. Запись в Outbox (Третья запись в БД)
        saveOutbox(order.getId(), "ORDER", "ORDER_CREATED", order);

        log.info("[FINANCIAL-CORE] Atomic transaction committed for order: {}", order.getId());
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