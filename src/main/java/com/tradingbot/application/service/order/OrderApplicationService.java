package com.tradingbot.application.service.order;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.tracing.*;
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
    private final RiskService riskService;
    private final OutboxService outboxService;
    private final ExecutionLogger executionLogger;

    @Transactional
    public void onSignalReceived(SignalEvent signal) {
        createOrder(signal.getIdentity(), signal.getAttempt(), signal.getBusiness(), signal);
    }

    @Transactional
    public void createOrder(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business, SignalEvent signal) {

        log.info("[TRACE_FLOW] ENTER createOrder signalId={}", identity.signalId());

        Optional<Order> orderOpt = riskService.evaluateAndReserve(signal);
        if (orderOpt.isEmpty()) {
            log.warn("[TRACE_FLOW] ORDER_REJECTED signalId={}", identity.signalId());
            return;
        }

        Order order = orderOpt.get();

        // 🔒 защита от коррапта идентичности
        if (order.getId().equals(identity.signalId())) {
            throw new IllegalStateException(
                    "Security violation: orderId must not equal signalId"
            );
        }

        // 📌 создаём бизнес-контекст ордера (новая SSOT ветка)
        BusinessContext orderBusiness =
                ExecutionPipeline.createBusiness(
                        attempt,
                        order.getId().toString(),
                        java.util.Map.of()
                );

        // 💾 persist
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);

        // 📤 outbox event
        outboxService.publishEvent(
                identity,
                attempt,
                orderBusiness,
                "ORDER",
                "ORDER_CREATED",
                order
        );

        // 📊 execution trace log
        executionLogger.log(
                ExecutionLogFactory.from(
                        order,
                        identity,
                        attempt,
                        orderBusiness,
                        ExecutionEventType.ORDER_CREATED,
                        ExecutionStateMapper.toContractState(order.getStatus()),
                        "Order created from signal " + signal.getSymbol()
                )
        );

        log.info("[TRACE_FLOW] EXIT ORDER_CREATED signalId={}", identity.signalId());
    }
}