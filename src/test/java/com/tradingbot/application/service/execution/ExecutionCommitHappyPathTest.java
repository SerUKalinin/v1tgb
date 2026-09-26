package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCommitHappyPathTest {

    @Test
    void executingOrderMustCommitToFilledWithCanonicalIdentity() {

        Order order =
                Order.createPendingExecution(
                        UUID.randomUUID(),
                        "client-" + UUID.randomUUID(),
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "test-strategy",
                        UUID.randomUUID()
                );

        ExecutionContext context =
                ExecutionContext.of(order);

        order.markExecuting(
                context
        );

        assertThat(order.getStatus())
                .isEqualTo(
                        OrderStatus.EXECUTING
                );

        assertThat(order.getExecutionAttempts())
                .isEqualTo(1);

        UUID canonicalExecutionId =
                order.getExecutionId();

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        OrderCompensationService compensationService =
                mock(OrderCompensationService.class);

        OutboxService outboxService =
                mock(OutboxService.class);

        ExecutionLockService lockService =
                mock(ExecutionLockService.class);

        when(
                lockService.markExecuted(
                        "lock-key"
                )
        ).thenReturn(true);

        OrderExecutionCommitService service =
                new OrderExecutionCommitService(
                        orderRepository,
                        compensationService,
                        outboxService,
                        lockService
                );

        OutboxEvent event =
                mock(OutboxEvent.class);

        ExecutionResult result =
                ExecutionResult.filled(
                        order.getId(),
                        "exchange-order-1",
                        "exchange-trade-1",
                        order.getSymbol(),
                        order.getSide(),
                        BigDecimal.ONE,
                        new BigDecimal("101"),
                        BigDecimal.ZERO,
                        "USDT",
                        order.getClientOrderId()
                );

        service.commit(
                event,
                context,
                order,
                result,
                "lock-key"
        );

        assertThat(order.getStatus())
                .as("normal execution commit must reach FILLED")
                .isEqualTo(
                        OrderStatus.FILLED
                );

        assertThat(order.getExecutionId())
                .as("execution identity must remain immutable")
                .isEqualTo(
                        canonicalExecutionId
                );

        assertThat(order.getExecutedQuantity())
                .isEqualByComparingTo(
                        BigDecimal.ONE
                );

        assertThat(order.getAveragePrice())
                .isEqualByComparingTo(
                        new BigDecimal("101")
                );

        assertThat(order.getExchangeOrderId())
                .isEqualTo(
                        "exchange-order-1"
                );

        verify(
                compensationService
        ).consumeReservation(
                order,
                "Order fully filled"
        );

        verify(
                orderRepository
        ).save(
                order
        );

        verify(
                outboxService
        ).publishEvent(
                any(),
                anyString(),
                anyString(),
                any()
        );

        verify(
                lockService
        ).markExecuted(
                "lock-key"
        );
    }
}