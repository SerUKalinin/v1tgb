package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.event.OutboxEventRouter;
import com.tradingbot.domain.model.OutboxEvent;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OutboxCausalOrderingIntegrationTest
        extends BaseIntegrationTest {

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private OutboxEventRouter outboxEventRouter;

    @Test
    @DisplayName(
            "F3: outbox must preserve causal ordering within aggregate"
    )
    void shouldNotProcessLaterEventBeforeEarlierEvent()
            throws Exception {

        // ============================================================
        // GIVEN — CANONICAL EXECUTION IDENTITY
        // ============================================================

        UUID signalId =
                UUID.randomUUID();

        UUID orderId =
                UUID.randomUUID();

        UUID executionId =
                UUID.randomUUID();

        UUID aggregateId =
                orderId;

        UUID eventId1 =
                UUID.randomUUID();

        UUID eventId2 =
                UUID.randomUUID();

        Instant createdAt =
                Instant.now();

        // ============================================================
        // GIVEN — SEQUENCE 1
        // ============================================================

        OutboxEventEntity firstEvent =
                OutboxEventEntity.builder()
                        .id(eventId1)
                        .eventId(eventId1)
                        .aggregateId(aggregateId)
                        .aggregateType("ORDER")
                        .eventType("ORDER_CREATED")
                        .payload("{}")
                        .status(OutboxStatus.NEW)
                        .retryCount(0)
                        .attemptCount(0)
                        .sequenceNumber(1L)
                        .schemaVersion(1)
                        .signalId(signalId)
                        .orderId(orderId)
                        .executionId(executionId)
                        .causationId(signalId)
                        .correlationId(signalId)
                        .createdAt(createdAt)
                        .updatedAt(createdAt)
                        .build();

        // ============================================================
        // GIVEN — SEQUENCE 2
        // ============================================================

        OutboxEventEntity secondEvent =
                OutboxEventEntity.builder()
                        .id(eventId2)
                        .eventId(eventId2)
                        .aggregateId(aggregateId)
                        .aggregateType("ORDER")
                        .eventType("ORDER_EXECUTED")
                        .payload("{}")
                        .status(OutboxStatus.NEW)
                        .retryCount(0)
                        .attemptCount(0)
                        .sequenceNumber(2L)
                        .schemaVersion(1)
                        .signalId(signalId)
                        .orderId(orderId)
                        .executionId(executionId)
                        .causationId(eventId1)
                        .correlationId(signalId)
                        .createdAt(
                                createdAt.plusMillis(1)
                        )
                        .updatedAt(
                                createdAt.plusMillis(1)
                        )
                        .build();

        outboxEventRepository.saveAndFlush(
                firstEvent
        );

        outboxEventRepository.saveAndFlush(
                secondEvent
        );

        // ============================================================
        // GIVEN — SEQUENCE 1 FAILS FIRST
        // ============================================================

        doThrow(
                new RuntimeException(
                        "SIMULATED_SEQUENCE_1_FAILURE"
                )
        )
                .doAnswer(
                        invocation -> null
                )
                .when(outboxEventRouter)
                .route(any(OutboxEvent.class));

        // ============================================================
        // WHEN — FIRST PROCESSOR RUN
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — SEQUENCE 1 FAILED
        // ============================================================

        OutboxEventEntity failedFirst =
                outboxEventRepository
                        .findById(eventId1)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.FAILED,
                failedFirst.getStatus(),
                "Sequence 1 must become FAILED"
        );

        assertEquals(
                1,
                failedFirst.getRetryCount(),
                "Sequence 1 retryCount must become 1"
        );

        assertEquals(
                1,
                failedFirst.getAttemptCount(),
                "Sequence 1 must have exactly one processing attempt"
        );

        // ============================================================
        // THEN — SEQUENCE 2 MUST REMAIN NEW
        // ============================================================

        OutboxEventEntity secondAfterFirstAttempt =
                outboxEventRepository
                        .findById(eventId2)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.NEW,
                secondAfterFirstAttempt.getStatus(),
                "Sequence 2 must remain NEW while sequence 1 is not processed"
        );

        assertEquals(
                0,
                secondAfterFirstAttempt.getAttemptCount(),
                "Sequence 2 must not be claimed before sequence 1"
        );

        verify(
                outboxEventRouter,
                times(1)
        ).route(
                any(OutboxEvent.class)
        );

        // ============================================================
        // GIVEN — RETRY SEQUENCE 1
        // ============================================================

        failedFirst.setNextAttemptAt(
                Instant.now().minusSeconds(1)
        );

        outboxEventRepository.saveAndFlush(
                failedFirst
        );

        // ============================================================
        // WHEN — RETRY SEQUENCE 1
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — SEQUENCE 1 PROCESSED
        // ============================================================

        OutboxEventEntity processedFirst =
                outboxEventRepository
                        .findById(eventId1)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.PROCESSED,
                processedFirst.getStatus(),
                "Sequence 1 must become PROCESSED on retry"
        );

        assertEquals(
                1,
                processedFirst.getRetryCount(),
                "Sequence 1 retryCount must remain 1"
        );

        // ============================================================
        // THEN — SEQUENCE 2 IS STILL NEW
        // ============================================================

        OutboxEventEntity secondBeforeNextBatch =
                outboxEventRepository
                        .findById(eventId2)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.NEW,
                secondBeforeNextBatch.getStatus(),
                "Sequence 2 must remain NEW until the next processor batch"
        );

        assertEquals(
                0,
                secondBeforeNextBatch.getAttemptCount(),
                "Sequence 2 must not have been claimed prematurely"
        );

        // ============================================================
        // WHEN — NEXT PROCESSOR RUN
        // ============================================================

        outboxProcessor.processOutbox();

        // ============================================================
        // THEN — SEQUENCE 2 PROCESSED
        // ============================================================

        OutboxEventEntity processedSecond =
                outboxEventRepository
                        .findById(eventId2)
                        .orElseThrow();

        assertEquals(
                OutboxStatus.PROCESSED,
                processedSecond.getStatus(),
                "Sequence 2 must process after sequence 1"
        );

        assertEquals(
                1,
                processedSecond.getAttemptCount(),
                "Sequence 2 must be claimed exactly once"
        );

        // ============================================================
        // THEN — EXACT DISPATCH ORDER
        // ============================================================

        ArgumentCaptor<OutboxEvent> captor =
                ArgumentCaptor.forClass(
                        OutboxEvent.class
                );

        verify(
                outboxEventRouter,
                times(3)
        ).route(
                captor.capture()
        );

        List<OutboxEvent> dispatched =
                captor.getAllValues();

        assertNotNull(
                dispatched
        );

        assertEquals(
                3,
                dispatched.size()
        );

        /*
         * Attempt 1:
         *     sequence 1 -> failure
         *
         * Attempt 2:
         *     sequence 1 -> success
         *
         * Attempt 3:
         *     sequence 2 -> success
         */
        assertEquals(
                1L,
                dispatched.get(0).sequenceNumber()
        );

        assertEquals(
                1L,
                dispatched.get(1).sequenceNumber()
        );

        assertEquals(
                2L,
                dispatched.get(2).sequenceNumber()
        );
    }
}