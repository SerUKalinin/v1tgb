package com.tradingbot.infrastructure.execution.fake;

import com.tradingbot.application.service.execution.TradeService;
import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
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
    public ExecutionResult execute(ApprovedOrder approvedOrder) {
        log.info("[FAKE-EXEC] Executing order: {} {} {} @ {}",
                approvedOrder.getSide(), approvedOrder.getQuantity(), approvedOrder.getSymbol(), approvedOrder.getPrice());

        String externalOrderId = "fake-order-" + UUID.randomUUID().toString().substring(0, 8);
        String externalTradeId = "fake-trade-" + UUID.randomUUID().toString().substring(0, 8);

        // Прямой вызов TradeService вместо публикации события
        tradeService.onOrderFilled(new OrderFilledEvent(
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
