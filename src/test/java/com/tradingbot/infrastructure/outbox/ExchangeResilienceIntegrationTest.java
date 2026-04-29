package com.tradingbot.infrastructure.outbox;

import com.tradingbot.BaseIntegrationTest;
import com.tradingbot.application.event.OutboxEventRouter;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@ActiveProfiles("test")
class ExchangeResilienceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private OutboxEventRouter outboxEventRouter;

    @Test
    void shouldRetryOnExchangeFailureAndEventuallySucceed() throws Exception {
        outboxEventRepository.deleteAll();

        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(UUID.randomUUID())
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload("{}")
                .status(OutboxStatus.NEW)
                .retryCount(0)
                .attemptCount(0)
                .build();
        outboxEventRepository.saveAndFlush(event);

        // First call fails, second succeeds
        doThrow(new RuntimeException("Exchange Down"))
                .doAnswer(invocation -> null)
                .when(outboxEventRouter).route(any());

        // When
        outboxProcessor.processOutbox(); // First attempt - fails

        // Then (Synchronous check, no await needed)
        OutboxEventEntity failedEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failedEvent.getRetryCount()).isEqualTo(1);

        // Force next attempt time to past to bypass backoff
        failedEvent.setNextAttemptAt(java.time.Instant.now().minusSeconds(1));
        outboxEventRepository.saveAndFlush(failedEvent);

        // When: Second attempt - succeeds
        outboxProcessor.processOutbox(); 

        // Then
        OutboxEventEntity successEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(successEvent.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }
}
