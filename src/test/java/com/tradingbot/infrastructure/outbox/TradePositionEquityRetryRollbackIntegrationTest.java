package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.service.risk.EquityService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.IdentityFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TradePositionEquityRetryRollbackIntegrationTest
        extends BaseIntegrationTest {

    private static final String EQUITY_CONSUMER =
            "EquityProjectionHandler";

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private EquitySnapshotRepository equitySnapshotRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @SpyBean
    private EquityService equityService;

    @BeforeEach
    void cleanProjectionState() {

        processedEventRepository.deleteAll();
        equitySnapshotRepository.deleteAll();
        positionRepository.deleteAll();
        outboxEventRepository.deleteAll();

        riskStateRepository
                .findById(
                        com.tradingbot.infrastructure.persistence.entity.RiskStateEntity.SINGLETON_ID
                )
                .ifPresent(riskState -> {

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
                });
    }

    @Test
    @DisplayName(
            "F4.2: failed TRADE_CREATED must rollback Position and Equity, then retry exactly once"
    )
    void shouldRollbackProjectionAndRetryExactlyOnce()
            throws Exception {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        String strategyId =
                "F4-RETRY-" + UUID.randomUUID();

        /*
         * Canonical identity chain:
         *
         * signalId
         *     ↓
         * orderId
         *     ↓
         * executionId
         */
        IdentityContext identity =
                IdentityContext.of(signalId);

        ExecutionAttemptContext attempt =
                ExecutionAttemptContext.firstAttemptForOrder(
                        orderId
                );

        UUID executionId =
                attempt.executionId();

        BusinessContext business =
                BusinessContext.of(
                        orderId.toString()
                );

        ExecutionContext context =
                ExecutionContext.of(
                        identity,
                        attempt,
                        business
                );

        ExecutionContext tradeContext =
                context.withNextStep(
                        IdentityFactory.deriveEventId(
                                executionId,
                                "trade-publish"
                        )
                );

        UUID tradeId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "trade"
                );

        UUID eventId =
                IdentityFactory.deriveEventId(
                        executionId,
                        "TRADE_CREATED"
                );

        UUID equityConsumerMarkerId =
                IdentityFactory.deriveEventId(
                        eventId,
                        "consumer:" + EQUITY_CONSUMER
                );

        TradeCreatedEvent tradeCreatedEvent =
                new TradeCreatedEvent(
                        tradeContext.identity(),
                        tradeContext.attempt(),
                        tradeContext.business(),
                        tradeId,
                        orderId,
                        "BTCUSDT",
                        strategyId,
                        new BigDecimal("1.0"),
                        new BigDecimal("100"),
                        OrderSide.BUY,
                        null,
                        null
                );

        outboxService.publishEvent(
                tradeContext,
                "TRADE",
                "TRADE_CREATED",
                tradeCreatedEvent
        );

        /*
         * Первый EquityService вызов:
         *
         * 1. реально выполняем onTradeCreated()
         * 2. после успешной business mutation бросаем exception
         *
         * Exception должен попасть в OutboxProcessor transaction
         * и откатить всю downstream transaction.
         */
        doAnswer(
                (Answer<Void>) invocation -> {

                    invocation.callRealMethod();

                    throw new RuntimeException(
                            "SIMULATED_EQUITY_FAILURE_AFTER_MUTATION"
                    );
                }
        )
                .doCallRealMethod()
                .when(equityService)
                .onTradeCreated(
                        any(TradeCreatedEvent.class)
                );

        // ============================================================
        // FIRST DELIVERY
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — OUTBOX FAILED
        // ============================================================

        OutboxEventEntity failedEvent =
                outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "TRADE_CREATED event must exist"
                                )
                        );

        assertEquals(
                OutboxStatus.FAILED,
                failedEvent.getStatus(),
                "Failed consumer must move TRADE_CREATED to FAILED"
        );

        assertEquals(
                1,
                failedEvent.getRetryCount(),
                "First failure must increment retryCount to 1"
        );

        // ============================================================
        // THEN — POSITION ROLLED BACK
        // ============================================================

        assertFalse(
                positionRepository
                        .findBySymbolAndStrategyId(
                                "BTCUSDT",
                                strategyId
                        )
                        .isPresent(),
                "Position mutation must rollback after downstream failure"
        );

        // ============================================================
        // THEN — EQUITY ROLLED BACK
        // ============================================================

        List<EquitySnapshotEntity> snapshotsAfterFailure =
                equitySnapshotRepository.findAll();

        assertEquals(
                0,
                snapshotsAfterFailure.size(),
                "Equity snapshot must rollback after downstream failure"
        );

        // ============================================================
        // THEN — POSITION IDEMPOTENCY MARKER ROLLED BACK
        // ============================================================

        assertFalse(
                processedEventRepository
                        .findById(eventId)
                        .isPresent(),
                "Position idempotency marker must rollback with Position mutation"
        );

        // ============================================================
        // THEN — EQUITY IDEMPOTENCY MARKER ROLLED BACK
        // ============================================================

        assertFalse(
                processedEventRepository
                        .findById(equityConsumerMarkerId)
                        .isPresent(),
                "Equity idempotency marker must rollback with snapshot mutation"
        );

        // ============================================================
        // RETRY WINDOW
        // ============================================================

        failedEvent.setNextAttemptAt(
                Instant.now().minusSeconds(1)
        );

        outboxEventRepository.saveAndFlush(
                failedEvent
        );

        // ============================================================
        // SECOND DELIVERY — REAL EXECUTION
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — OUTBOX PROCESSED
        // ============================================================

        OutboxEventEntity processedEvent =
                outboxEventRepository
                        .findById(eventId)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.PROCESSED,
                processedEvent.getStatus(),
                "Retry must successfully process TRADE_CREATED"
        );

        assertEquals(
                1,
                processedEvent.getRetryCount(),
                "Successful retry must preserve retryCount=1"
        );

        // ============================================================
        // THEN — POSITION CREATED EXACTLY ONCE
        // ============================================================

        PositionEntity positionAfterRetry =
                positionRepository
                        .findBySymbolAndStrategyId(
                                "BTCUSDT",
                                strategyId
                        )
                        .orElseThrow();

        assertNotNull(
                positionAfterRetry
        );

        assertBigDecimal(
                "1.0",
                positionAfterRetry.getQuantity(),
                "position.quantity after retry"
        );

        // ============================================================
        // THEN — EQUITY CREATED EXACTLY ONCE
        // ============================================================

        List<EquitySnapshotEntity> snapshotsAfterRetry =
                equitySnapshotRepository.findAll();

        assertEquals(
                1,
                snapshotsAfterRetry.size(),
                "Exactly one equity snapshot must exist after successful retry"
        );

        EquitySnapshotEntity snapshot =
                snapshotsAfterRetry.get(0);

        assertEquals(
                strategyId,
                snapshot.getStrategyId()
        );

        // ============================================================
        // THEN — POSITION MARKER EXISTS
        // ============================================================

        assertEquals(
                true,
                processedEventRepository
                        .findById(eventId)
                        .isPresent(),
                "Position idempotency marker must exist after successful retry"
        );

        // ============================================================
        // THEN — EQUITY MARKER EXISTS
        // ============================================================

        assertEquals(
                true,
                processedEventRepository
                        .findById(equityConsumerMarkerId)
                        .isPresent(),
                "Equity idempotency marker must exist after successful retry"
        );

        // ============================================================
        // THEN — EQUITY CONSUMER CALLED TWICE
        // ============================================================

        verify(
                equityService,
                times(2)
        ).onTradeCreated(
                any(TradeCreatedEvent.class)
        );
    }

    private void assertBigDecimal(
            String expected,
            BigDecimal actual,
            String message
    ) {

        assertNotNull(
                actual,
                message + ": value is null"
        );

        assertEquals(
                0,
                actual.compareTo(
                        new BigDecimal(expected)
                ),
                message +
                        ": expected=" + expected +
                        ", actual=" + actual
        );
    }
}