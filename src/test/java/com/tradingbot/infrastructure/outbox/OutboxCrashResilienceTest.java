package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.event.OutboxEventRouter;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class OutboxCrashResilienceTest extends BaseIntegrationTest {

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @MockBean
    private OutboxEventRouter outboxEventRouter;


    @Test
    void shouldRecoverAfterCrashBetweenExchangeSideEffectAndDbCommit() throws Exception {

        // arrange
        outboxRepository.deleteAll();

        UUID eventId = UUID.randomUUID();

        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(UUID.randomUUID())
                .sequenceNumber(1L) // Обязательное поле в новой архитектуре
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload("{}")
                .status(OutboxStatus.NEW)
                .build();
        outboxRepository.saveAndFlush(event);

        AtomicInteger exchangeCalls = new AtomicInteger();

        doAnswer(inv -> {
            exchangeCalls.incrementAndGet();
            throw new RuntimeException("CRASH_BEFORE_COMMIT");
        }).doAnswer(inv -> {
            exchangeCalls.incrementAndGet();
            return null;
        }).when(outboxEventRouter).route(any());


        // act #1 — simulated crash
        outboxProcessor.processOutbox();


        // assert recovery state after crash
        OutboxEventEntity afterCrash =
                outboxRepository.findById(eventId).orElseThrow();

        assertThat(afterCrash.getStatus())
                .isEqualTo(OutboxStatus.FAILED);

        assertThat(exchangeCalls.get())
                .isEqualTo(1);


        // act #2 — retry after restart
        // Force next attempt time to past to bypass backoff
        afterCrash.setNextAttemptAt(java.time.Instant.now().minusSeconds(1));
        outboxRepository.saveAndFlush(afterCrash);

        outboxProcessor.processOutbox();


        // assert final recovery
        OutboxEventEntity finalState =
                outboxRepository.findById(eventId).orElseThrow();

        assertThat(finalState.getStatus())
                .isEqualTo(OutboxStatus.PROCESSED);

        assertThat(exchangeCalls.get())
                .isEqualTo(2);
    }
}