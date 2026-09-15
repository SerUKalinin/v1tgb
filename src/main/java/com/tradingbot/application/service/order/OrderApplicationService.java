package com.tradingbot.application.service.order;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionEventType;
import com.tradingbot.tracing.ExecutionLogFactory;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.ExecutionStateMapper;
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
    public boolean handleSignal(SignalEvent signal) {

        ExecutionContext signalContext = signal.getExecutionContext();

        log.info(
                "[TRACE_FLOW] ENTER ORDER_EVALUATION signalId={}",
                signalContext.signalId()
        );

        // 1. Проверка рисков и создание модели ордера через RiskEngine
        Optional<Order> orderOpt = riskEngine.evaluateSignal(
                signalContext,
                signal
        );

        // 2. Risk отклонил сигнал — Order не существует
        if (orderOpt.isEmpty()) {
            log.warn(
                    "[TRACE_FLOW] ORDER_REJECTED signalId={}",
                    signalContext.signalId()
            );
            return false;
        }

        Order order = orderOpt.get();

        // 3. Инвариант идентичности:
        //    signalId и orderId — разные identity
        if (order.getId().equals(signalContext.signalId())) {
            throw new IllegalStateException(
                    "Security violation: orderId must not equal signalId"
            );
        }

        /*
         * 4. Order execution identity SSOT.
         *
         * Нельзя переиспользовать signalContext через withBusiness(...),
         * потому что он сохраняет ExecutionAttemptContext сигнала.
         *
         * Order имеет собственный immutable executionId.
         */
        ExecutionContext orderContext = ExecutionContext.of(order);

        // 5. Persist ордера
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);

        /*
         * 6. ORDER_CREATED обязан использовать executionId самого Order.
         */
        outboxService.publishEvent(
                orderContext,
                "ORDER",
                "ORDER_CREATED",
                new OrderCreatedEvent(
                        order.getSignalId(),
                        order.getId(),
                        order.getExecutionId()
                )
        );

        // 7. Execution tracing
        executionLogger.log(
                ExecutionLogFactory.from(
                        order,
                        orderContext,
                        ExecutionEventType.ORDER_CREATED,
                        ExecutionStateMapper.toContractState(order.getStatus()),
                        "Order created from signal " + signal.getSymbol()
                )
        );

        log.info(
                "[TRACE_FLOW] EXIT ORDER_CREATED orderId={} executionId={}",
                order.getId(),
                order.getExecutionId()
        );

        return true;
    }
}