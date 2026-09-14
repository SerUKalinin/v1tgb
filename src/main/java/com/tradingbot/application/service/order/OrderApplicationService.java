package com.tradingbot.application.service.order;

import com.tradingbot.application.risk.RiskEngine;
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
    private final RiskEngine riskEngine;
    private final OutboxService outboxService;
    private final ExecutionLogger executionLogger;

    @Transactional
    public void handleSignal(SignalEvent signal) {
        ExecutionContext context = signal.getExecutionContext();
        log.info("[TRACE_FLOW] ENTER ORDER_CREATED signalId={}", context.signalId());

        // 1. Проверка рисков и создание модели ордера через RiskEngine
        Optional<Order> orderOpt = riskEngine.evaluateSignal(context, signal);
        if (orderOpt.isEmpty()) {
            log.warn("Order creation rejected by RiskService for signalId={}", context.signalId());
            return;
        }

        Order order = orderOpt.get();

        // 2. Защита от коррапта идентичности (инвариант: orderId != signalId)
        if (order.getId().toString().equals(context.signalId().toString())) {
            throw new IllegalStateException("Security violation: orderId must not equal signalId");
        }

        // 3. Создание бизнес-контекста ордера (новая SSOT ветка)
        ExecutionContext orderContext = context.withBusiness(BusinessContext.of(order.getId().toString()));

        // 4. Сохранение в БД
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);

        // 5. Публикация события в Outbox с использованием строго типизированного DTO
        outboxService.publishEvent(
                orderContext,
                "ORDER",
                "ORDER_CREATED",
                new OrderCreatedEvent(
                        orderContext.signalId(),
                        order.getId(),
                        orderContext.attempt().executionId()
                )
        );

        // 6. Логирование трассировки исполнения
        executionLogger.log(
                ExecutionLogFactory.from(
                        order,
                        orderContext,
                        ExecutionEventType.ORDER_CREATED,
                        ExecutionStateMapper.toContractState(order.getStatus()),
                        "Order created from signal " + signal.getSymbol()
                )
        );

        log.info("[TRACE_FLOW] EXIT ORDER_CREATED orderId={}", order.getId());
    }
}
