package com.tradingbot.application.service.risk;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconciliationRecoveryTest {

    private ReconciliationService reconciliationService;
    private OrderRepositoryPort orderRepository;
    private ExchangeOrderQueryService exchangeQueryService;

    @BeforeEach
    void setUp() {

        orderRepository =
                mock(OrderRepositoryPort.class);

        exchangeQueryService =
                mock(ExchangeOrderQueryService.class);

        reconciliationService =
                new ReconciliationService(
                        orderRepository,
                        null,
                        exchangeQueryService,
                        mock(OrderCompensationService.class),
                        mock(RiskEngine.class),
                        null,
                        null,
                        null,
                        mock(TransitionValidator.class),
                        mock(ExecutionLogger.class)
                );
    }

    @Test
    void shouldRecoverStuckUnknownOrderToFilled() {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        Order order =
                Order.createPendingExecution(
                        orderId,
                        "client-1",
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        BigDecimal.valueOf(50000),
                        "strat-1",
                        signalId
                );

        /*
         * PENDING_EXECUTION -> EXECUTING -> UNKNOWN
         */
        ExecutionContext context =
                ExecutionContext.of(order);

        order.markExecuting(context);
        order.markAsUnknown(context);

        assertEquals(
                OrderStatus.UNKNOWN,
                order.getStatus()
        );

        when(
                orderRepository.claimForReconciliation(
                        orderId
                )
        ).thenReturn(
                Optional.of(order)
        );

        when(
                exchangeQueryService.getOrderStatus(
                        "BTCUSDT",
                        "client-1"
                )
        ).thenReturn(
                ExecutionResult.filled(
                        orderId,
                        "ex-123",
                        "trade-123",
                        "BTCUSDT",
                        OrderSide.BUY,
                        BigDecimal.ONE,
                        BigDecimal.valueOf(50000),
                        BigDecimal.ZERO,
                        "USDT",
                        "client-1"
                )
        );

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.FILLED,
                order.getStatus()
        );

        assertEquals(
                "ex-123",
                order.getExchangeOrderId()
        );

        assertEquals(
                BigDecimal.ONE,
                order.getExecutedQuantity()
        );

        assertEquals(
                BigDecimal.valueOf(50000),
                order.getAveragePrice()
        );

        verify(
                exchangeQueryService,
                times(1)
        ).getOrderStatus(
                "BTCUSDT",
                "client-1"
        );

        verify(
                orderRepository,
                atLeastOnce()
        ).save(any());
    }
}