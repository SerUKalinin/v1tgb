package com.tradingbot.infrastructure.execution.fake;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@Profile("backtest")
@Slf4j
@RequiredArgsConstructor
public class BacktestExecutionEngine implements ExecutionEngine {

    private final TradeService tradeService;

    @Override
    public ExecutionResult execute(Order order) {
        log.info("[FAKE-EXEC] Executing order: {} {} {} @ {}",
                order.getSide(), order.getQuantity(), order.getSymbol(), order.getPrice());

        String externalOrderId = "fake-order-" + UUID.randomUUID().toString().substring(0, 8);
        String externalTradeId = "fake-trade-" + UUID.randomUUID().toString().substring(0, 8);

        // Восстанавливаем контекст из ордера (в бэктесте aggregateId = signalId)
        ExecutionContext context = ExecutionContext.init(order.getSignalId())
                .attachOrder(order.getId(), UUID.randomUUID())
                .startExecutionAttempt(UUID.randomUUID(), UUID.randomUUID());

        // Прямой вызов TradeService вместо публикации события
        tradeService.onOrderFilled(new OrderFilledEvent(
                context,
                order.getId(),
                externalTradeId,
                order.getSymbol(),
                order.getQuantity(),
                order.getPrice()
        ));

        return ExecutionResult.success(
                order.getId(),
                externalOrderId,
                externalTradeId,
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getPrice(),
                order.getQuantity().multiply(new BigDecimal("0.001")),
                "USDT",
                order.getClientOrderId()
        );
    }
    @Override
    public ExecutionResult verifyOrder(String clientOrderId) {
        log.info("[FAKE-EXEC] Verifying order: {}", clientOrderId);
        // В режиме бэктеста считаем, что если мы здесь, то ордер был исполнен
        return ExecutionResult.builder()
                .exchangeOrderId("fake-recon-" + clientOrderId)
                .executedQty(BigDecimal.ZERO)
                .status(ExecutionResult.Status.SUCCESS) // Исправлено здесь
                .build();
    }
}
