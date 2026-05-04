package com.tradingbot.application.service.order;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.event.OrderEventPayload;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final RiskManager riskManager;
    private final OutboxService outboxService;

    @Transactional
    public void onSignalReceived(SignalEvent signal) {
        createOrder(signal);
    }

    @Transactional
    public void createOrder(SignalEvent signal) {
        // 1. Risk Check
        Optional<Order> approved = riskManager.approveSignal(signal);
        if (approved.isEmpty()) return;

        Order order = approved.get();

        // 2. Save Entity
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);

        // 3. Save Outbox Event using stable DTO
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

        outboxService.publishEvent(order.getId(), "ORDER", "ORDER_CREATED", payload);

        log.info("[FINANCIAL-CORE] Order created and outbox saved: {}", order.getId());
    }}
