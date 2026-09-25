package com.tradingbot.application.service.execution;

import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.domain.execution.ExecutionOwnershipException;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ExecutionCommitIdentityConsistencyTest {

    @Test
    void commitMustRejectForeignSignalIdEvenWhenExecutionIdMatches() {

        Order order =
                Order.createPendingExecution(
                        UUID.randomUUID(),
                        "client-" + UUID.randomUUID(),
                        "BTCUSDT",
                        com.tradingbot.common.enums.OrderSide.BUY,
                        com.tradingbot.common.enums.OrderType.LIMIT,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "test",
                        UUID.randomUUID()
                );

        UUID canonicalSignalId =
                order.getSignalId();

        UUID foreignSignalId =
                UUID.randomUUID();

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.recover(
                        canonicalSignalId,
                        order.getExecutionId(),
                        1
                );

        ExecutionContext foreignContext =
                new ExecutionContext(
                        IdentityContext.of(
                                foreignSignalId
                        ),
                        attempt,
                        BusinessContext.of(
                                order.getId().toString()
                        )
                );

        OutboxEvent event =
                mock(OutboxEvent.class);

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        OrderCompensationService compensationService =
                mock(OrderCompensationService.class);

        OutboxService outboxService =
                mock(OutboxService.class);

        ExecutionLockService lockService =
                mock(ExecutionLockService.class);

        OrderExecutionCommitService service =
                new OrderExecutionCommitService(
                        orderRepository,
                        compensationService,
                        outboxService,
                        lockService
                );

        ExecutionResult result =
                ExecutionResult.accepted(
                        "exchange-order-1",
                        order.getClientOrderId()
                );

        assertThatThrownBy(
                () ->
                        service.commit(
                                event,
                                foreignContext,
                                order,
                                result,
                                "lock-key"
                        )
        )
                .isInstanceOf(
                        ExecutionOwnershipException.class
                )
                .hasMessageContaining(
                        "signalId"
                );

        verify(
                orderRepository,
                never()
        ).save(
                any()
        );

        verify(
                outboxService,
                never()
        ).publishEvent(
                any(),
                anyString(),
                anyString(),
                any()
        );

        verify(
                lockService,
                never()
        ).markExecuted(
                anyString()
        );
    }

    @Test
    void commitMustRejectForeignOrderIdEvenWhenExecutionIdMatches() {

        Order order =
                Order.createPendingExecution(
                        UUID.randomUUID(),
                        "client-" + UUID.randomUUID(),
                        "BTCUSDT",
                        com.tradingbot.common.enums.OrderSide.BUY,
                        com.tradingbot.common.enums.OrderType.LIMIT,
                        BigDecimal.ONE,
                        new BigDecimal("100"),
                        "test",
                        UUID.randomUUID()
                );

        UUID signalId =
                order.getSignalId();

        UUID foreignOrderId =
                UUID.randomUUID();

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.recover(
                        signalId,
                        order.getExecutionId(),
                        1
                );

        ExecutionContext foreignContext =
                new ExecutionContext(
                        IdentityContext.of(
                                signalId
                        ),
                        attempt,
                        BusinessContext.of(
                                foreignOrderId.toString()
                        )
                );

        OutboxEvent event =
                mock(OutboxEvent.class);

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        OrderCompensationService compensationService =
                mock(OrderCompensationService.class);

        OutboxService outboxService =
                mock(OutboxService.class);

        ExecutionLockService lockService =
                mock(ExecutionLockService.class);

        OrderExecutionCommitService service =
                new OrderExecutionCommitService(
                        orderRepository,
                        compensationService,
                        outboxService,
                        lockService
                );

        ExecutionResult result =
                ExecutionResult.accepted(
                        "exchange-order-1",
                        order.getClientOrderId()
                );

        assertThatThrownBy(
                () ->
                        service.commit(
                                event,
                                foreignContext,
                                order,
                                result,
                                "lock-key"
                        )
        )
                .isInstanceOf(
                        ExecutionOwnershipException.class
                )
                .hasMessageContaining(
                        "orderId"
                );

        verify(
                orderRepository,
                never()
        ).save(
                any()
        );

        verify(
                outboxService,
                never()
        ).publishEvent(
                any(),
                anyString(),
                anyString(),
                any()
        );

        verify(
                lockService,
                never()
        ).markExecuted(
                anyString()
        );
    }
}