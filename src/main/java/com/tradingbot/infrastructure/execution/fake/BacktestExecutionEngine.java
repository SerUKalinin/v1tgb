package com.tradingbot.infrastructure.execution.fake;

import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Profile("backtest")
@Slf4j
@RequiredArgsConstructor
public class BacktestExecutionEngine implements ExecutionEngine {
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public ExecutionResult execute(ApprovedOrder approvedOrder) {
        log.info("[FAKE-EXEC] Executing order: {} {} {} @ {}", 
                approvedOrder.getSide(), approvedOrder.getQuantity(), approvedOrder.getSymbol(), approvedOrder.getPrice());

        String externalOrderId = "fake-order-" + UUID.randomUUID().toString().substring(0, 8);
        String externalTradeId = "fake-trade-" + UUID.randomUUID().toString().substring(0, 8);

        // Публикуем событие об исполнении (минимальный контракт)
        eventPublisher.publishEvent(new OrderFilledEvent(
                approvedOrder.getOrderId(),
                externalTradeId,
                approvedOrder.getSymbol(),
                approvedOrder.getQuantity(),
                approvedOrder.getPrice()
        ));

        return ExecutionResult.success(
                approvedOrder.getOrderId(),
                externalOrderId,
                externalTradeId,
                approvedOrder.getSymbol(),
                approvedOrder.getSide(),
                approvedOrder.getQuantity(),
                approvedOrder.getPrice(),
                approvedOrder.getQuantity().multiply(new BigDecimal("0.001")),
                "USDT",
                approvedOrder.getClientOrderId()
        );
    }
}