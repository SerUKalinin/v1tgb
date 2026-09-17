package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.OrderCompensationService;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.execution.PositionRebuildService;
import com.tradingbot.application.service.system.AdminNotificationService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.model.OutboxRecoveryPort;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReconciliationUnknownStateTest {

    private OrderRepositoryPort orderRepository;
    private OutboxRecoveryPort outboxRecoveryPort;
    private ExchangeOrderQueryService exchangeQueryService;
    private OrderCompensationService orderCompensationService;
    private RiskEngine riskEngine;
    private AdminNotificationService notifications;
    private PositionRebuildService positionRebuildService;
    private SystemStateManager stateManager;
    private TransitionValidator transitionValidator;
    private ExecutionLogger executionLogger;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {

        orderRepository =
                mock(OrderRepositoryPort.class);

        outboxRecoveryPort =
                mock(OutboxRecoveryPort.class);

        exchangeQueryService =
                mock(ExchangeOrderQueryService.class);

        orderCompensationService =
                mock(OrderCompensationService.class);

        riskEngine =
                mock(RiskEngine.class);

        notifications =
                mock(AdminNotificationService.class);

        positionRebuildService =
                mock(PositionRebuildService.class);

        stateManager =
                mock(SystemStateManager.class);

        transitionValidator =
                mock(TransitionValidator.class);

        executionLogger =
                mock(ExecutionLogger.class);

        reconciliationService =
                new ReconciliationService(
                        orderRepository,
                        outboxRecoveryPort,
                        exchangeQueryService,
                        orderCompensationService,
                        riskEngine,
                        notifications,
                        positionRebuildService,
                        stateManager,
                        transitionValidator,
                        executionLogger
                );
    }

    @Test
    void exchangeStateUnknownMustMoveExecutingOrderToUnknown()
            throws Exception {

        UUID orderId =
                UUID.randomUUID();

        UUID signalId =
                UUID.randomUUID();

        UUID executionId =
                IdentityFactory.deriveExecution(
                        orderId,
                        1
                );

        String clientOrderId =
                "CL-" + orderId;

        Order order =
                Order.createPendingExecution(
                        orderId,
                        clientOrderId,
                        "BTCUSDT",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        BigDecimal.ONE,
                        new BigDecimal("50000"),
                        "strategy-1",
                        signalId
                );

        /*
         * Воспроизводим persisted EXECUTING state
         * после успешного claim перед exchange I/O.
         */
        ReflectionTestUtils.setField(
                order,
                "executionId",
                executionId
        );

        ReflectionTestUtils.setField(
                order,
                "executionAttempts",
                1
        );

        ReflectionTestUtils.setField(
                order,
                "executionStartedAt",
                Instant.now().minusSeconds(180)
        );

        ReflectionTestUtils.setField(
                order,
                "status",
                OrderStatus.EXECUTING
        );

        ExecutionContext context =
                ExecutionContext.of(order);

        when(
                orderRepository.claimForReconciliation(
                        eq(orderId)
                )
        ).thenReturn(
                Optional.of(order)
        );

        when(
                exchangeQueryService.getOrderStatus(
                        eq(clientOrderId)
                )
        ).thenReturn(
                ExecutionResult.exchangeStateUnknown(
                        orderId
                )
        );

        reconciliationService.syncOrderWithExchange(
                order,
                context
        );

        assertEquals(
                OrderStatus.UNKNOWN,
                order.getStatus(),
                "Exchange state unknown must move EXECUTING order to UNKNOWN"
        );

        assertEquals(
                executionId,
                order.getExecutionId(),
                "ExecutionId must remain preserved during UNKNOWN recovery"
        );

        assertNotNull(
                order.getExecutionStartedAt(),
                "Execution start timestamp must remain present"
        );

        verify(
                orderRepository,
                times(1)
        ).save(
                eq(order)
        );

        verifyNoInteractions(
                orderCompensationService
        );
    }
}