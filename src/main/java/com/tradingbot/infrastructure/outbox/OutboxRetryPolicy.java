package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class OutboxRetryPolicy {

    private static final int MAX_RETRIES = 5;
    private static final long BASE_DELAY_SECONDS = 2;

    public boolean shouldRetry(OutboxEventEntity event) {
        return event.getRetryCount() < MAX_RETRIES;
    }

    public boolean isReadyForRetry(OutboxEventEntity event) {
        if (event.getStatus() == OutboxStatus.NEW) {
            return true;
        }
        if (event.getStatus() != OutboxStatus.FAILED) {
            return false;
        }

        // Exponential backoff: base * 2^retryCount
        long delaySeconds = BASE_DELAY_SECONDS * (long) Math.pow(2, event.getRetryCount() - 1);
        Instant nextAttempt = event.getUpdatedAt().plus(Duration.ofSeconds(delaySeconds));
        
        return Instant.now().isAfter(nextAttempt);
    }

    public int getMaxRetries() {
        return MAX_RETRIES;
    }
}
