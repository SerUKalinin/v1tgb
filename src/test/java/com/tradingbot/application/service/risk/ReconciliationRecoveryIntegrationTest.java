package com.tradingbot.application.service.risk;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class ReconciliationRecoveryIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private OrderRepositoryPort orderRepository;

    @MockBean
    private ExchangeOrderQueryService exchangeQueryService;

    @MockBean
    private RiskEngine riskEngine;

    @MockBean
    private ExecutionLogger executionLogger;

    @Test
    void shouldRecoverStuckUnknownOrderToFilled() {
        // 1. Arrange: Order in UNKNOWN state (e.g. after exchange timeout)
        UUID orderId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        Order order = Order.createPendingExecution(
                orderId, "client-1", "BTCUSDT", 
                com.tradingbot.common.enums.OrderSide.BUY, 
                com.tradingbot.common.enums.OrderType.MARKET, 
                BigDecimal.ONE, BigDecimal.valueOf(50000), 
                "strat-1", signalId
        );
        
        // Manually move to UNKNOWN to simulate timeout
        ExecutionContext context = ExecutionContext.of(order);
        order.assignExecutionOwner(context);
        order.markExecuting(context);
        order.markAsUnknown(context);
        orderRepository.save(order);

        // Mock exchange response: Order actually succeeded
        when(exchangeQueryService.getOrderStatus(anyString()))
                .thenReturn(ExecutionResult.builder()
                        .status(ExecutionResult.Status.SUCCESS)
                        .exchangeOrderId("ex-123")
                        .executedQty(BigDecimal.ONE)
                        .executedPrice(BigDecimal.valueOf(50000))
                        .build());

        // 2. Act: Run reconciliation
        reconciliationService.syncOrderWithExchange(order, context);

        // 3. Assert: Order should be FILLED
        Order recoveredOrder = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.FILLED, recoveredOrder.getStatus());
        assertEquals("ex-123", recoveredOrder.getExchangeOrderId());
    }
}
