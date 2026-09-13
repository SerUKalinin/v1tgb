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

/**
 * Application service, отвечающий за создание ордера на основе торгового сигнала.
 *
 * <p>Является оркестратором use-case уровня "Signal → Order creation":
 * <ul>
 *     <li>валидация через RiskEngine</li>
 *     <li>формирование доменной модели Order</li>
 *     <li>персист в БД</li>
 *     <li>публикация Outbox события ORDER_CREATED</li>
 *     <li>фиксация execution tracing</li>
 * </ul>
 *
 * <p>Слой не содержит торговой логики — только orchestration.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final RiskEngine riskEngine;
    private final OutboxService outboxService;
    private final ExecutionLogger executionLogger;

    /**
     * Обрабатывает торговый сигнал и инициирует создание ордера.
     *
     * <p>Pipeline:
     * <ol>
     *     <li>Risk evaluation (RiskEngine)</li>
     *     <li>Domain Order creation</li>
     *     <li>Persistence</li>
     *     <li>Outbox event publication</li>
     *     <li>Execution logging</li>
     * </ol>
     *
     * @param signal торговый сигнал, инициирующий создание ордера
     */
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

        // 2. Инвариант идентичности (signalId ≠ orderId)
        if (order.getId().toString().equals(context.signalId().toString())) {
            throw new IllegalStateException("Security violation: orderId must not equal signalId");
        }

        // 3. Формирование бизнес-контекста ордера (новая SSOT ветка)
        ExecutionContext orderContext = context.withBusiness(
                BusinessContext.of(order.getId().toString())
        );

        // 4. Persist ордера
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);

        // 5. Outbox событие ORDER_CREATED
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

        // 6. Execution tracing
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