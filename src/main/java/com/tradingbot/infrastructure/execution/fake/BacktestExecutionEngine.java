package com.tradingbot.infrastructure.execution.fake;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.event.OrderRejectedEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Profile("backtest")
@Slf4j
@RequiredArgsConstructor
public class BacktestExecutionEngine implements ExecutionEngine {
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public ExecutionResult execute(OrderRequest request) {
        log.info("[FAKE-EXEC] Executing order: {} {} {} @ {}", 
                request.getSide(), request.getAmount(), request.getSymbol(), request.getPrice());

        String externalOrderId = "fake-order-" + UUID.randomUUID().toString().substring(0, 8);
        String externalTradeId = "fake-trade-" + UUID.randomUUID().toString().substring(0, 8);

        // Публикуем событие об исполнении (минимальный контракт)
        eventPublisher.publishEvent(new OrderFilledEvent(
                request.getOrderId(),
                externalTradeId,
                request.getSymbol(),
                request.getAmount(),
                request.getPrice()
        ));

        return ExecutionResult.success(
                request.getOrderId(),
                externalOrderId,
                externalTradeId,
                request.getSymbol(),
                request.getSide(),
                request.getAmount(),
                request.getPrice(),
                request.getAmount().multiply(new BigDecimal("0.001")),
                "USDT",
                request.getClientOrderId()
        );
    }
}