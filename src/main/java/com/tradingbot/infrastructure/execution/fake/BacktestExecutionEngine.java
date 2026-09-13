package com.tradingbot.infrastructure.execution.fake;

import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * FAKE EXECUTION ENGINE (Backtest)
 * Симулирует исполнение ордеров без реального обращения к бирже.
 */
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

        String externalOrderId = "fake-order-" + IdentityFactory.deriveEventId(order.getSignalId(), "external-order").toString().substring(0, 8);
        String externalTradeId = "fake-trade-" + IdentityFactory.deriveEventId(order.getSignalId(), "external-trade").toString().substring(0, 8);

        ExecutionContext context = ExecutionContext.of(order).withTransportRetry();

        tradeService.onOrderFilled(new OrderFilledEvent(
                context.identity(),
                context.attempt(),
                context.business(),
                order.getId(),
                externalTradeId,
                order.getSymbol(),
                order.getQuantity(),
                order.getPrice()
        ));

        return ExecutionResult.filled(
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

        return ExecutionResult.accepted(
                "fake-recon-" + clientOrderId,
                clientOrderId
        );
    }
}
