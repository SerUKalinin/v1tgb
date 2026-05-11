package com.tradingbot.application.service.order;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.event.OrderEventPayload;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
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
    private final RiskService riskService;
    private final OutboxService outboxService;
    private final ExecutionLogger executionLogger;

    @Transactional
    public void onSignalReceived(ExecutionContext context, SignalEvent signal) {
        createOrder(context, signal);
    }

    @Transactional
    public void createOrder(ExecutionContext context, SignalEvent signal) {
        log.info("[TRACE_FLOW] ENTER OrderApplicationService.createOrder context: {}", context);
        log.info("[TRACE_FLOW] [RISK_STARTED] context={}", context);

        Optional<Order> orderOpt = riskService.evaluateAndReserve(signal);

        if (orderOpt.isEmpty()) {
            log.warn("[TRACE_FLOW] EXIT - ORDER_REJECTED: Risk check failed for context {}", context);
            return;
        }

        Order order = orderOpt.get();
        // causationId теперь ссылается на eventId входящего сигнала
        ExecutionContext orderContext = context.attachOrder(order.getId(), signal.getEventId());
        
        log.info("[TRACE_FLOW] [RISK_COMPLETED] context={}", orderContext);
        log.info("[TRACE_FLOW] Order approved and capital reserved: {}", order.getId());

        OrderEntity entity = orderMapper.toEntity(order);
        entity.setCreatedAt(Instant.now());
        orderRepository.save(entity);
        log.info("[TRACE_FLOW] [ORDER_PERSISTED] context={}", orderContext);

        OrderEventPayload payload = OrderEventPayload.builder()
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .status(order.getStatus().name())
                .timestamp(Instant.now())
                .strategyId(order.getStrategyId())
                .signalId(orderContext.signalId().toString())
                .causationId(orderContext.causationId())
                .correlationId(orderContext.correlationId())
                .build();
        
        outboxService.publishEvent(orderContext, "ORDER", "ORDER_CREATED", payload);
        log.info("[TRACE_FLOW] Outbox event published with context");

        executionLogger.log(ExecutionLogFactory.from(
                order,
                orderContext,
                ExecutionEventType.ORDER_CREATED,
                ExecutionStateMapper.toContractState(order.getStatus()),
                "Order created from signal " + signal.getSymbol()
        ));

        log.info("[TRACE_FLOW] EXIT - ORDER_CREATED: context {}", orderContext);
    }
}
