package com.tradingbot.infrastructure.outbox;

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

@SpringBootTest
@ActiveProfiles("test")
public class ExchangeResilienceIntegrationTest {

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private OutboxDispatcher outboxDispatcher;

    @Test
    void shouldRetryOnExchangeFailureAndEventuallySucceed() throws Exception {        outboxEventRepository.deleteAll();

        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.builder()
                .id(eventId)
                .aggregateId(UUID.randomUUID())
                .aggregateType("ORDER")
                .eventType("ORDER_CREATED")
                .payload("{}")
                .status(OutboxStatus.NEW)
                .build();
        outboxEventRepository.save(event);

        // First call fails, second succeeds
        doThrow(new RuntimeException("Exchange Down"))
                .doNothing()
                .when(outboxDispatcher).dispatch(any());

        // When
        outboxProcessor.processOutbox(); // First attempt - fails

        // Then
        OutboxEventEntity failedEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(failedEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failedEvent.getRetryCount()).isEqualTo(1);

        // When
        outboxProcessor.processOutbox(); // Second attempt - succeeds

        // Then
        OutboxEventEntity successEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(successEvent.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }
}
