package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TradePositionEquityExactlyOnceIntegrationTest
        extends BaseIntegrationTest {

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
            "F4: duplicate TRADE_CREATED must not duplicate Position or Equity"
    )
    void shouldProcessTradeCreatedExactlyOnce()
            throws Exception {

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        String strategyId =
                "F4-EXACTLY-ONCE-" + UUID.randomUUID();

        /*
         * Canonical identity chain:
         *
         * signalId
         *     ↓
         * orderId
         *     ↓
         * executionId
         *
         * executionId MUST come from ExecutionAttemptContext,
         * not from an independently generated/derived value.
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

        /*
         * Создаём единственное canonical TRADE_CREATED event.
         */
        outboxService.publishEvent(
                tradeContext,
                "TRADE",
                "TRADE_CREATED",
                tradeCreatedEvent
        );

        /*
         * Первичная доставка:
         *
         * TRADE_CREATED
         *      ↓
         * PositionProjectionHandler
         *      ↓
         * Position
         *      ↓
         * EquityProjectionHandler
         *      ↓
         * Equity snapshot
         */
        outboxProcessor.processOutbox();

        OutboxEventEntity firstDelivery =
                outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(
                                () -> new AssertionError(
                                        "TRADE_CREATED outbox event not found: "
                                                + eventId
                                )
                        );

        assertEquals(
                OutboxStatus.PROCESSED,
                firstDelivery.getStatus()
        );

        PositionEntity positionAfterFirstDelivery =
                positionRepository
                        .findBySymbolAndStrategyId(
                                "BTCUSDT",
                                strategyId
                        )
                        .orElseThrow();

        assertBigDecimal(
                "1.0",
                positionAfterFirstDelivery.getQuantity(),
                "position.quantity after first delivery"
        );

        List<EquitySnapshotEntity> snapshotsAfterFirstDelivery =
                equitySnapshotRepository.findAll();

        assertEquals(
                1,
                snapshotsAfterFirstDelivery.size(),
                "Exactly one equity projection must exist after first delivery"
        );

        /*
         * Duplicate delivery.
         *
         * Возвращаем ТО ЖЕ самое outbox-событие в NEW.
         * eventId, payload и executionId остаются неизменными.
         *
         * Это моделирует повторную доставку одного TRADE_CREATED.
         */
        firstDelivery.setStatus(
                OutboxStatus.NEW
        );

        firstDelivery.setProcessedAt(null);
        firstDelivery.setLastError(null);
        firstDelivery.setNextAttemptAt(null);
        firstDelivery.setLockOwner(null);
        firstDelivery.setLockedUntil(null);
        firstDelivery.setClaimedBy(null);
        firstDelivery.setClaimedAt(null);
        firstDelivery.setLeaseUntil(null);

        outboxEventRepository.saveAndFlush(
                firstDelivery
        );

        /*
         * Повторная доставка того же самого eventId.
         */
        outboxProcessor.processOutbox();

        OutboxEventEntity secondDelivery =
                outboxEventRepository
                        .findById(eventId)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.PROCESSED,
                secondDelivery.getStatus()
        );

        /*
         * Position НЕ должна измениться второй раз.
         */
        PositionEntity positionAfterDuplicate =
                positionRepository
                        .findBySymbolAndStrategyId(
                                "BTCUSDT",
                                strategyId
                        )
                        .orElseThrow();

        assertNotNull(
                positionAfterDuplicate
        );

        assertBigDecimal(
                "1.0",
                positionAfterDuplicate.getQuantity(),
                "position.quantity after duplicate delivery"
        );

        /*
         * Equity также должна быть создана ровно один раз.
         *
         * На текущем production-коде это место должно показать
         * фактический результат F4.
         */
        List<EquitySnapshotEntity> snapshotsAfterDuplicate =
                equitySnapshotRepository.findAll();

        assertEquals(
                1,
                snapshotsAfterDuplicate.size(),
                "Duplicate TRADE_CREATED must not create a second equity projection"
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