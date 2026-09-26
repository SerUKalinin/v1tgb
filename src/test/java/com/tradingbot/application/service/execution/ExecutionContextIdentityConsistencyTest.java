package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.order.OrderCreatedEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.execution.ExecutionLockService;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionContextIdentityConsistencyTest {

    @Test
    void contextSignalIdMustMatchOrderCreatedSignalId() {

        SystemStateManager stateManager =
                mock(SystemStateManager.class);

        ExecutionClaimPort executionClaimPort =
                mock(ExecutionClaimPort.class);

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        TransitionValidator transitionValidator =
                mock(TransitionValidator.class);

        ExecutionLockService executionLockService =
                mock(ExecutionLockService.class);

        when(
                stateManager.isReady()
        ).thenReturn(true);

        OrderExecutionClaimService service =
                new OrderExecutionClaimService(
                        stateManager,
                        executionClaimPort,
                        orderRepository,
                        transitionValidator,
                        executionLockService
                );

        UUID canonicalSignalId =
                UUID.randomUUID();

        UUID foreignSignalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        orderId
                );

        ExecutionContext context =
                new ExecutionContext(
                        IdentityContext.of(
                                foreignSignalId
                        ),
                        attempt,
                        BusinessContext.of(
                                orderId.toString()
                        )
                );

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        canonicalSignalId,
                        orderId,
                        attempt.executionId()
                );

        assertThatThrownBy(
                () ->
                        service.claim(
                                null,
                                context,
                                payload
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "signalId"
                );

        verify(
                orderRepository,
                never()
        ).claimForExecution(
                any(),
                any()
        );

        verify(
                executionClaimPort,
                never()
        ).claimExecution(
                any(),
                any()
        );

        verify(
                executionLockService,
                never()
        ).claimForExecution(
                any()
        );
    }

    @Test
    void contextOrderIdMustMatchOrderCreatedOrderId() {

        SystemStateManager stateManager =
                mock(SystemStateManager.class);

        ExecutionClaimPort executionClaimPort =
                mock(ExecutionClaimPort.class);

        OrderRepositoryPort orderRepository =
                mock(OrderRepositoryPort.class);

        TransitionValidator transitionValidator =
                mock(TransitionValidator.class);

        ExecutionLockService executionLockService =
                mock(ExecutionLockService.class);

        when(
                stateManager.isReady()
        ).thenReturn(true);

        OrderExecutionClaimService service =
                new OrderExecutionClaimService(
                        stateManager,
                        executionClaimPort,
                        orderRepository,
                        transitionValidator,
                        executionLockService
                );

        UUID signalId =
                UUID.randomUUID();

        UUID canonicalOrderId =
                UUID.randomUUID();

        UUID foreignOrderId =
                UUID.randomUUID();

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        canonicalOrderId
                );

        ExecutionContext context =
                new ExecutionContext(
                        IdentityContext.of(
                                signalId
                        ),
                        attempt,
                        BusinessContext.of(
                                foreignOrderId.toString()
                        )
                );

        OrderCreatedEvent payload =
                new OrderCreatedEvent(
                        signalId,
                        canonicalOrderId,
                        attempt.executionId()
                );

        assertThatThrownBy(
                () ->
                        service.claim(
                                null,
                                context,
                                payload
                        )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "orderId"
                );

        verify(
                orderRepository,
                never()
        ).claimForExecution(
                any(),
                any()
        );

        verify(
                executionClaimPort,
                never()
        ).claimExecution(
                any(),
                any()
        );

        verify(
                executionLockService,
                never()
        ).claimForExecution(
                any()
        );
    }
}