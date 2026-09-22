package com.tradingbot.application;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExecutionPort;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.infrastructure.outbox.OutboxProcessor;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
        properties = {
                "app.outbox.enabled=true"
        }
)
@ActiveProfiles("test")
class FullExecutionPipelineIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ExecutionClaimRepository claimRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private RiskStateRepository riskStateRepository;

    @Autowired
    private SignalExecutionFacade signalExecutionFacade;

    @Autowired
    private OutboxProcessor outboxProcessor;

    /*
     * Production bootstrap не должен запускать настоящий startup flow
     * внутри этого integration test.
     */
    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @MockBean
    private SystemStateManager systemStateManager;

    @MockBean
    private ExecutionPort executionPort;

    @BeforeEach
    void prepareTestEnvironment() {

        /*
         * OutboxProcessor проверяет:
         * 1. enabled
         * 2. trading enabled
         * 3. system ready
         */
        when(systemStateManager.isTradingEnabled())
                .thenReturn(true);

        when(systemStateManager.isReady())
                .thenReturn(true);

        /*
         * Используем реальный RiskStateRepository.
         * Никакого @MockBean RiskStatePort здесь нет.
         */
        riskStateRepository.deleteAll();

        RiskStateEntity riskState =
                new RiskStateEntity();

        riskState.setId(
                RiskStateEntity.SINGLETON_ID
        );

        riskState.setTotalEquity(
                new BigDecimal("10000")
        );

        riskState.setAvailableBalance(
                new BigDecimal("10000")
        );

        riskState.setReservedMargin(
                BigDecimal.ZERO
        );

        riskState.setHalted(false);

        riskState.setUpdatedAt(
                Instant.now()
        );

        riskStateRepository.saveAndFlush(
                riskState
        );

        /*
         * Exchange IO заменяем детерминированным mock.
         */
        when(
                executionPort.placeOrder(
                        any()
                )
        ).thenAnswer(invocation -> {

            var order =
                    invocation.getArgument(
                            0,
                            com.tradingbot.domain.model.Order.class
                    );

            return ExecutionResult.filled(
                    order.getId(),
                    "TEST-EXCHANGE-ORDER-" + order.getId(),
                    "TEST-EXCHANGE-TRADE-" + order.getId(),
                    order.getSymbol(),
                    order.getSide(),
                    order.getQuantity(),
                    order.getPrice(),
                    BigDecimal.ZERO,
                    "USDT",
                    order.getClientOrderId()
            );
        });
    }

    @Test
    void testFullPipelineIdempotencyAndDeterminism() {

        UUID signalId =
                UUID.randomUUID();

        SignalEvent signal =
                new SignalEvent(
                        signalId,
                        "BTCUSDT",
                        SignalType.BUY,
                        new BigDecimal("50000"),
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        Instant.now(),
                        "STRAT-1"
                );

        /*
         * ============================================================
         * 1. FIRST PASS
         *
         * Signal
         *   -> Risk
         *   -> Reservation
         *   -> Order
         *   -> ORDER_CREATED
         *   -> Outbox
         *   -> Claim
         *   -> EXECUTING
         *   -> ExecutionPort
         *   -> FILLED
         *   -> ORDER_EXECUTED
         *   -> TRADE_CREATED
         * ============================================================
         */

        signalExecutionFacade.execute(
                signal
        );

        drainOutbox();

        OrderEntity order1 =
                waitForFilledOrder(
                        signalId
                );

        assertEquals(
                OrderStatus.FILLED,
                order1.getStatus()
        );

        UUID executionId1 =
                order1.getExecutionId();

        assertNotNull(
                executionId1,
                "ExecutionId must be assigned"
        );

        UUID expectedOrderId =
                com.tradingbot.tracing.IdentityFactory
                        .deriveOrder(signalId);

        UUID expectedExecutionId =
                com.tradingbot.tracing.IdentityFactory
                        .deriveExecution(
                                expectedOrderId,
                                1
                        );

        assertEquals(
                expectedOrderId,
                order1.getId(),
                "OrderId must be canonically derived from signalId"
        );

        assertEquals(
                expectedExecutionId,
                executionId1,
                "Order executionId must be canonically derived from orderId and first attempt"
        );

        assertEquals(
                1,
                order1.getExecutionAttempts(),
                "Exactly one execution attempt expected"
        );

        assertEquals(
                expectedExecutionId,
                order1.getExecutionId(),
                "First execution must use canonical executionId"
        );

        assertTrue(
                claimRepository.existsBySignalId(
                        signalId
                ),
                "Execution claim must exist"
        );

        assertEquals(
                1,
                orderRepository.countBySignalId(
                        signalId
                ),
                "Exactly one Order must exist"
        );

        assertEquals(
                1,
                claimRepository.countBySignalId(
                        signalId
                ),
                "Exactly one ExecutionClaim must exist"
        );

        /*
         * ============================================================
         * 2. VERIFY REAL DB RISK STATE
         * ============================================================
         */

        RiskStateEntity riskAfterExecution =
                riskStateRepository
                        .findById(
                                RiskStateEntity.SINGLETON_ID
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "RiskState must exist"
                                )
                        );

        /*
         * ВАЖНО:
         * BigDecimal.equals() сравнивает scale:
         *
         * 0       != 0E-18
         *
         * Поэтому здесь сравниваем числовое значение через compareTo().
         */
        assertEquals(
                0,
                riskAfterExecution
                        .getReservedMargin()
                        .compareTo(BigDecimal.ZERO),
                "BUY reservation must be consumed after FILLED"
        );

        /*
         * Запоминаем lifecycle events до повторной подачи
         * того же самого сигнала.
         */
        Set<UUID> outboxEventIdsBeforeDuplicate =
                getAllOutboxEventIds();

        assertFalse(
                outboxEventIdsBeforeDuplicate.isEmpty(),
                "Outbox should contain lifecycle events"
        );

        /*
         * ============================================================
         * 3. SECOND PASS — SAME SIGNAL
         * ============================================================
         */

        signalExecutionFacade.execute(
                signal
        );

        drainOutbox();

        /*
         * ============================================================
         * 4. IDEMPOTENCY
         * ============================================================
         */

        OrderEntity order2 =
                orderRepository
                        .findBySignalId(
                                signalId
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Order must still exist"
                                )
                        );

        assertEquals(
                order1.getId(),
                order2.getId(),
                "Repeated signal must resolve to same Order"
        );

        assertEquals(
                executionId1,
                order2.getExecutionId(),
                "ExecutionId must stay unchanged"
        );

        assertEquals(
                OrderStatus.FILLED,
                order2.getStatus(),
                "Terminal order must remain FILLED"
        );

        assertEquals(
                1,
                orderRepository.countBySignalId(
                        signalId
                ),
                "Duplicate Order must not be created"
        );

        assertEquals(
                1,
                claimRepository.countBySignalId(
                        signalId
                ),
                "Duplicate ExecutionClaim must not be created"
        );

        assertEquals(
                outboxEventIdsBeforeDuplicate,
                getAllOutboxEventIds(),
                "Duplicate signal must not create new lifecycle events"
        );

        /*
         * ============================================================
         * 5. OUTBOX SSOT
         * ============================================================
         */

        outboxRepository
                .findAll()
                .forEach(event -> {

                    assertNotNull(
                            event.getCorrelationId(),
                            "CorrelationId must be present"
                    );

                    assertNotNull(
                            event.getCausationId(),
                            "CausationId must be present"
                    );

                    assertEquals(
                            signalId,
                            event.getSignalId(),
                            "SignalId must be preserved"
                    );

                    assertEquals(
                            executionId1,
                            event.getExecutionId(),
                            "ExecutionId must remain consistent"
                    );
                });

        /*
         * Финальная проверка RiskState после всей цепочки.
         */
        RiskStateEntity finalRiskState =
                riskStateRepository
                        .findById(
                                RiskStateEntity.SINGLETON_ID
                        )
                        .orElseThrow(
                                () -> new AssertionError(
                                        "Final RiskState must exist"
                                )
                        );

        assertEquals(
                0,
                finalRiskState
                        .getReservedMargin()
                        .compareTo(BigDecimal.ZERO),
                "No active BUY reservation must remain after settlement"
        );
    }

    private OrderEntity waitForFilledOrder(
            UUID signalId
    ) {

        Instant deadline =
                Instant.now()
                        .plus(
                                Duration.ofSeconds(15)
                        );

        OrderEntity current =
                null;

        while (
                Instant.now().isBefore(deadline)
        ) {

            current =
                    orderRepository
                            .findBySignalId(
                                    signalId
                            )
                            .orElse(null);

            if (
                    current != null
                            && current.getStatus()
                            == OrderStatus.FILLED
            ) {
                return current;
            }

            sleep(100);
        }

        if (current == null) {

            fail(
                    "Order was not created within 15 seconds. " +
                            "signalId=" + signalId
            );
        }

        fail(
                "Order was not FILLED within 15 seconds. " +
                        "Current order: " +
                        "status=" + current.getStatus() +
                        ", orderId=" + current.getId() +
                        ", executionId=" + current.getExecutionId() +
                        ", attempts=" + current.getExecutionAttempts() +
                        ", signalId=" + current.getSignalId()
        );

        return current;
    }

    private void drainOutbox() {

        Set<UUID> previousIds =
                new HashSet<>();

        for (int i = 0; i < 10; i++) {

            Set<UUID> currentIds =
                    getAllOutboxEventIds();

            outboxProcessor.processOutbox();

            Set<UUID> afterProcessing =
                    getAllOutboxEventIds();

            if (
                    afterProcessing.equals(
                            currentIds
                    )
            ) {
                return;
            }

            previousIds =
                    afterProcessing;
        }

        fail(
                "Outbox chain was not drained after 10 passes. " +
                        "Events=" + previousIds.size()
        );
    }

    private Set<UUID> getAllOutboxEventIds() {

        Set<UUID> ids =
                new HashSet<>();

        outboxRepository
                .findAll()
                .forEach(
                        event ->
                                ids.add(
                                        event.getId()
                                )
                );

        return ids;
    }

    private void sleep(
            long millis
    ) {

        try {

            Thread.sleep(
                    millis
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            fail(
                    "Test thread was interrupted"
            );
        }
    }
}